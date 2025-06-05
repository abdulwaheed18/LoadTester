package com.example.loadtester.service;

import com.example.loadtester.config.LoadTesterProperties;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy; // Import @Lazy
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class LoadEmitterService implements DisposableBean { // Implement DisposableBean to shutdown executor
    private static final Logger logger = LoggerFactory.getLogger(LoadEmitterService.class);

    private final LoadTesterProperties props;
    private final RateLimiterService rateLimiterService;
    private final PayloadLoader payloadLoader;
    private final WebClient webClient;
    private final MeterRegistry meterRegistry;
    private final SummaryReportingService summaryReportingService; // Added for optional report on timed stop

    private final Map<String, Disposable> activeEmitters = new ConcurrentHashMap<>();
    private final Map<String, String> payloadCache = new ConcurrentHashMap<>();
    private final AtomicBoolean emittersRunning = new AtomicBoolean(false);

    // Scheduler for timed stop of emitters
    private final ScheduledExecutorService timedStopScheduler;
    private ScheduledFuture<?> timedStopFuture;


    @Autowired
    public LoadEmitterService(
            LoadTesterProperties props,
            RateLimiterService rateLimiterService,
            PayloadLoader payloadLoader,
            WebClient.Builder webClientBuilder,
            MeterRegistry meterRegistry,
            @Lazy SummaryReportingService summaryReportingService // Autowire SummaryReportingService lazily
    ) {
        this.props = props;
        this.rateLimiterService = rateLimiterService;
        this.payloadLoader = payloadLoader;
        this.webClient = webClientBuilder.build();
        this.meterRegistry = meterRegistry;
        this.summaryReportingService = summaryReportingService;
        this.timedStopScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r);
            thread.setName("load-emitter-timed-stop-scheduler");
            thread.setDaemon(true); // Allows JVM to exit if this is the only active thread
            return thread;
        });
    }

    /**
     * Starts the load generation emitters for all configured targets.
     * If runDurationMinutes is configured, schedules a task to stop emitters after that duration.
     */
    public synchronized void startEmitters() {
        if (emittersRunning.get()) {
            logger.info("Load emitters are already running.");
            return;
        }

        List<LoadTesterProperties.TargetEndpoint> targets = props.getTargets();
        if (targets == null || targets.isEmpty()) {
            logger.warn("No targets configured. Cannot start emitters.");
            return;
        }

        // Cancel any existing timed stop future, e.g., if start is called again rapidly
        if (timedStopFuture != null && !timedStopFuture.isDone()) {
            timedStopFuture.cancel(false); // Don't interrupt if running, just prevent future execution
            logger.debug("Cancelled previous timed stop task as new session is starting.");
            timedStopFuture = null;
        }

        logger.info("Starting load emitters for {} targets...", targets.size());
        // Clear payload cache at the beginning of a new test session
        // This ensures fresh payloads are loaded if they might have changed externally
        // (though for classpath resources, this is less likely without an app restart).
        payloadCache.clear();

        for (LoadTesterProperties.TargetEndpoint endpoint : targets) {
            if (endpoint.getName() == null || endpoint.getName().isBlank()) {
                logger.warn("Skipping target with missing or blank name: {}", endpoint);
                continue;
            }
            String targetName = endpoint.getName();
            // Ensure no old emitter for this target is lingering (should be cleared by stopEmitters)
            if (activeEmitters.containsKey(targetName) && !activeEmitters.get(targetName).isDisposed()) {
                logger.warn("Emitter for target '{}' appears to be already active. This should not happen if stopEmitters was called. Skipping.", targetName);
                continue;
            }

            int desiredTps = endpoint.getDesiredTps();
            int throttleMs = endpoint.getThrottleIntervalMs();

            rateLimiterService.initializeBucket(targetName, throttleMs);

            HttpMethod method = HttpMethod.resolve(endpoint.getMethod().toUpperCase());
            if (method == null) {
                logger.error("Unsupported HTTP method '{}' for target '{}'. Skipping.", endpoint.getMethod(), targetName);
                continue;
            }
            // Pre-load payload for POST/PUT/PATCH requests
            if (endpoint.getPayloadPath() != null &&
                    (method == HttpMethod.POST || method == HttpMethod.PUT || method == HttpMethod.PATCH)) {
                try {
                    String body = payloadLoader.loadPayload(endpoint.getPayloadPath());
                    payloadCache.put(targetName, body); // Cache the loaded payload
                } catch (RuntimeException e) {
                    logger.error("Failed to load payload for target '{}' from path '{}'. Skipping emitter for this target. Error: {}",
                            targetName, endpoint.getPayloadPath(), e.getMessage());
                    continue; // Skip this target if its essential payload cannot be loaded
                }
            }

            // Register a gauge for desired TPS. MeterRegistry typically handles duplicates gracefully.
            Gauge.builder("loadtester.tps.desired", () -> endpoint.getDesiredTps())
                    .description("Desired transactions per second for the target")
                    .tag("target", targetName)
                    .register(meterRegistry);

            long intervalMs = (desiredTps <= 0) ? 1L : Math.max(1L, 1000L / desiredTps); // Ensure interval is at least 1ms

            Flux<Long> flux = Flux.interval(Duration.ofMillis(intervalMs))
                    .onBackpressureDrop(droppedTick -> {
                        logger.warn("[{}] Backpressure applied, request tick {} dropped.", targetName, droppedTick);
                        meterRegistry.counter("loadtester.requests.backpressure.dropped", "target", targetName).increment();
                    });

            Disposable emitterSubscription = flux.subscribe(tick -> {
                if (!emittersRunning.get()) { // Check if emitters should globally still be running
                    // This check helps to stop processing new ticks if stopEmitters was called.
                    // The subscription itself will be disposed by stopEmitters, but this adds an extra layer.
                    Disposable d = activeEmitters.get(targetName);
                    if(d != null && !d.isDisposed()) {
                        d.dispose(); // Proactively dispose this specific subscription if global flag is false
                    }
                    return;
                }
                if (!rateLimiterService.tryConsume(targetName)) {
                    logger.trace("[{}] tick={}, rate-limited by RateLimiterService, skipping", targetName, tick);
                    meterRegistry.counter("loadtester.requests.throttled", "target", targetName).increment();
                    return;
                }

                meterRegistry.counter("loadtester.requests.initiated", "target", targetName).increment();
                Timer.Sample requestTimerSample = Timer.start(meterRegistry);

                sendRequest(endpoint)
                        .doOnSuccess(status -> {
                            requestTimerSample.stop(meterRegistry.timer("loadtester.request.latency",
                                    Tags.of("target", targetName, "http_status", String.valueOf(status), "outcome", "success")));
                            meterRegistry.counter("loadtester.requests.completed",
                                    Tags.of("target", targetName, "http_status", String.valueOf(status), "outcome", "success")).increment();
                            meterRegistry.counter("loadtester.requests.by_status",
                                    Tags.of("target", targetName, "http_status", String.valueOf(status))).increment();
                            logger.trace("[{}] request #{} succeeded with status {}", targetName, tick, status);
                        })
                        .doOnError(error -> {
                            String httpStatusStr = "CLIENT_ERROR"; // Default for non-HTTP errors or before status is known
                            String errorType = error.getClass().getSimpleName();

                            if (error instanceof WebClientResponseException) {
                                WebClientResponseException wcre = (WebClientResponseException) error;
                                httpStatusStr = String.valueOf(wcre.getRawStatusCode());
                                // This counter for by_status is also incremented in the success path,
                                // so it correctly captures all responses that result in a status code.
                                meterRegistry.counter("loadtester.requests.by_status", // Ensure this is counted for errors too
                                        Tags.of("target", targetName, "http_status", httpStatusStr)).increment();
                            } else {
                                // For non-WebClientResponseException errors, still count them under a generic error status
                                meterRegistry.counter("loadtester.requests.by_status",
                                        Tags.of("target", targetName, "http_status", "CLIENT_ERROR")).increment();
                            }

                            requestTimerSample.stop(meterRegistry.timer("loadtester.request.latency",
                                    Tags.of("target", targetName, "http_status", httpStatusStr, "outcome", "failure")));
                            meterRegistry.counter("loadtester.requests.completed", // This is the primary counter for success/failure outcomes
                                    Tags.of("target", targetName, "http_status", httpStatusStr, "outcome", "failure", "error_type", errorType)).increment();
                            logger.warn("[{}] request #{} failed. Status: {}, Error Type: {}, Message: {}", targetName, tick, httpStatusStr, errorType, error.getMessage());
                        })
                        .subscribe(
                                status -> {}, // onNext - Handled in doOnSuccess
                                error -> {}   // onError - Handled in doOnError
                        );
            });

            activeEmitters.put(targetName, emitterSubscription);
            logger.info("Started emitter for target [{}] with configuration: Desired TPS: {}, Interval: {} ms, Throttle: {} ms",
                    targetName, (desiredTps == 0 ? "Max Effort" : String.valueOf(desiredTps)), intervalMs, throttleMs);
        }

        if (!activeEmitters.isEmpty()){
            emittersRunning.set(true);
            logger.info("All configured load emitters started successfully.");

            // Schedule timed stop if duration is configured
            int runDurationMinutes = props.getRunDurationMinutes();
            if (runDurationMinutes > 0) {
                logger.info("Load test session scheduled to run for {} minutes.", runDurationMinutes);
                timedStopFuture = timedStopScheduler.schedule(() -> {
                    logger.info("Configured run duration of {} minutes reached for the current load test session. Automatically stopping emitters.", runDurationMinutes);
                    stopEmitters(); // Call the existing stop method
                    // Optionally, trigger a summary report after timed stop
                    if (props.getReporting().getShutdown().isEnabled()) { // Re-purpose shutdown report flag
                        logger.info("Generating summary report after timed session completion (as reporting.shutdown.enabled is true).");
                        summaryReportingService.generateAndLogSummaryReport();
                    }
                }, runDurationMinutes, TimeUnit.MINUTES);
            } else {
                logger.info("Load test session configured to run indefinitely (until manually stopped via UI or application shutdown).");
            }

        } else {
            logger.warn("No emitters were started. This could be due to configuration issues or errors loading payloads for all targets.");
            emittersRunning.set(false); // Ensure state is correct if nothing actually started
        }
    }

    /**
     * Sends a single HTTP request based on the target endpoint configuration.
     * @param ep The target endpoint configuration.
     * @return A Mono emitting the HTTP status code on success, or an error.
     */
    private Mono<Integer> sendRequest(LoadTesterProperties.TargetEndpoint ep) {
        HttpMethod method = HttpMethod.resolve(ep.getMethod().toUpperCase());
        if (method == null) { // Should have been caught during startEmitters, but defensive check
            return Mono.error(new IllegalArgumentException("Unsupported method: " + ep.getMethod()));
        }

        WebClient.RequestBodySpec requestSpec = webClient
                .method(method)
                .uri(ep.getUrl());

        if (ep.getHeaders() != null) {
            ep.getHeaders().forEach(requestSpec::header);
        }

        WebClient.RequestHeadersSpec<?> finalRequestSpec = requestSpec; // Default to this if no body

        // Add body if it's a POST/PUT/PATCH and payload is available
        if (payloadCache.containsKey(ep.getName()) &&
                (method == HttpMethod.POST || method == HttpMethod.PUT || method == HttpMethod.PATCH)) {
            String body = payloadCache.get(ep.getName());
            String contentType = (ep.getHeaders() != null && ep.getHeaders().get("Content-Type") != null)
                    ? ep.getHeaders().get("Content-Type")
                    : MediaType.APPLICATION_JSON_VALUE; // Default to JSON if not specified
            finalRequestSpec = requestSpec.contentType(MediaType.parseMediaType(contentType)).bodyValue(body);
        } else if (ep.getPayloadPath() != null &&
                (method == HttpMethod.POST || method == HttpMethod.PUT || method == HttpMethod.PATCH) &&
                !payloadCache.containsKey(ep.getName())) {
            // This case means payloadPath was specified but loading failed or wasn't attempted for this target earlier
            logger.error("Payload for {} was specified but is not in cache. Request will likely fail or be sent without body.", ep.getName());
            // Depending on requirements, you might want to return Mono.error here if body is absolutely required.
            // For now, it proceeds, and WebClient might error or send without body.
        }


        return ((WebClient.RequestHeadersSpec<?>) finalRequestSpec) // Cast is safe here due to logic flow
                .exchangeToMono(response -> {
                    int rawStatusCode = response.rawStatusCode();
                    // Consume the body fully to release resources, regardless of success or error.
                    return response.bodyToMono(String.class)
                            .defaultIfEmpty("") // Ensure a body is processed even if empty, prevents hangs
                            .flatMap(bodyContent -> {
                                if (response.statusCode().isError()) {
                                    String errorBodySnippet = bodyContent.length() > 200 ? bodyContent.substring(0, 200) + "..." : bodyContent;
                                    logger.warn("Target '{}' request failed with HTTP {}. Response body snippet: {}", ep.getName(), rawStatusCode, errorBodySnippet);
                                    // Create a WebClientResponseException to propagate the error status and body
                                    return Mono.error(WebClientResponseException.create(
                                            rawStatusCode,
                                            HttpStatus.resolve(rawStatusCode) != null ? HttpStatus.resolve(rawStatusCode).getReasonPhrase() : "Unknown Status",
                                            response.headers().asHttpHeaders(),
                                            bodyContent.getBytes(), // Pass the full body to the exception
                                            null // Charset (can be null or determined from headers)
                                    ));
                                }
                                return Mono.just(rawStatusCode); // Success path
                            });
                });
    }

    /**
     * Stops all active load generation emitters and cancels any pending timed stop task.
     * This method is synchronized to prevent concurrent modification issues.
     */
    public synchronized void stopEmitters() {
        // Check if there's anything to stop
        if (!emittersRunning.get() && activeEmitters.isEmpty() && (timedStopFuture == null || timedStopFuture.isDone())) {
            logger.info("Load emitters are already stopped or were never started. No action taken.");
            return;
        }

        logger.info("Attempting to stop all active load emitters (currently {})...", activeEmitters.size());
        emittersRunning.set(false); // Set the global flag first to signal ongoing subscriptions to stop processing

        // Cancel any scheduled timed stop task
        if (timedStopFuture != null && !timedStopFuture.isDone()) {
            timedStopFuture.cancel(true); // true to interrupt if the task is running (though our task is just a stop call)
            logger.info("Cancelled any pending scheduled timed stop of emitters.");
            timedStopFuture = null; // Clear the future
        }

        // Dispose each active emitter's subscription
        activeEmitters.forEach((name, emitter) -> {
            if (emitter != null && !emitter.isDisposed()) {
                emitter.dispose(); // This stops the Flux.interval for this emitter
                logger.info("Disposed emitter for target [{}]", name);
            }
        });
        activeEmitters.clear(); // Clear the map of active emitters

        // payloadCache.clear(); // Clearing payload cache on stop might be desired if payloads could change between test runs
        // For now, payloads are loaded at the start of each session.

        // Note: Micrometer metrics are not reset here. They remain cumulative for the application's lifetime
        // or until the MeterRegistry is somehow reset, which is not typical for a running application.
        logger.info("All load emitters have been commanded to stop.");
    }

    /**
     * Checks if the load emitters are currently set to be active.
     * @return true if emitters are supposed to be running, false otherwise.
     */
    public boolean areEmittersRunning() {
        return emittersRunning.get();
    }

    /**
     * Called when the Spring application context is being destroyed.
     * Ensures that the internal scheduler is shut down gracefully.
     */
    @Override
    public void destroy() throws Exception {
        logger.info("Shutting down LoadEmitterService due to application context destruction.");
        stopEmitters(); // Ensure all emitters are stopped first

        if (timedStopScheduler != null && !timedStopScheduler.isShutdown()) {
            logger.info("Shutting down the timedStopScheduler...");
            timedStopScheduler.shutdown(); // Disable new tasks from being submitted
            try {
                // Wait a while for existing tasks to terminate
                if (!timedStopScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    logger.warn("TimedStopScheduler did not terminate in 5 seconds, forcing shutdown...");
                    timedStopScheduler.shutdownNow(); // Cancel currently executing tasks
                    // Wait a while for tasks to respond to being cancelled
                    if (!timedStopScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                        logger.error("TimedStopScheduler did not terminate even after forced shutdown.");
                    }
                }
            } catch (InterruptedException ie) {
                logger.error("Interrupted while waiting for TimedStopScheduler to terminate. Forcing shutdown.", ie);
                timedStopScheduler.shutdownNow();
                Thread.currentThread().interrupt(); // Preserve interrupt status
            }
            logger.info("TimedStopScheduler shutdown complete.");
        }
    }
}
