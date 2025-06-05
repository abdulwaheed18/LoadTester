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
import java.util.concurrent.atomic.AtomicReference;

@Service
public class LoadEmitterService implements DisposableBean { // Implement DisposableBean to shutdown executor
    private static final Logger logger = LoggerFactory.getLogger(LoadEmitterService.class);

    private final LoadTesterProperties props;
    private final RateLimiterService rateLimiterService;
    private final PayloadLoader payloadLoader;
    private final WebClient webClient;
    private final MeterRegistry meterRegistry;
    private final SummaryReportingService summaryReportingService;

    private final Map<String, Disposable> activeEmitters = new ConcurrentHashMap<>();
    private final Map<String, String> payloadCache = new ConcurrentHashMap<>();
    private final AtomicBoolean emittersRunning = new AtomicBoolean(false);
    private final AtomicReference<String> currentRunId = new AtomicReference<>(null); // To store the current run ID

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
            @Lazy SummaryReportingService summaryReportingService
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
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Starts the load generation emitters for all configured targets.
     * If runDurationMinutes is configured, schedules a task to stop emitters after that duration.
     * @param runId The unique identifier for this load test run.
     */
    public synchronized void startEmitters(String runId) { // runId parameter added
        if (emittersRunning.get()) {
            logger.info("Load emitters are already running under run ID: {}. Cannot start a new session.", this.currentRunId.get());
            return;
        }
        if (runId == null || runId.trim().isEmpty()) {
            logger.error("Run ID cannot be null or empty. Aborting startEmitters.");
            return;
        }
        this.currentRunId.set(runId); // Set the current run ID for this session
        logger.info("Attempting to start load emitters for run ID: {}", runId);


        List<LoadTesterProperties.TargetEndpoint> targets = props.getTargets();
        if (targets == null || targets.isEmpty()) {
            logger.warn("No targets configured for run ID: {}. Cannot start emitters.", runId);
            this.currentRunId.set(null); // Clear runId as nothing will start
            return;
        }

        if (timedStopFuture != null && !timedStopFuture.isDone()) {
            timedStopFuture.cancel(false);
            logger.debug("Cancelled previous timed stop task as new session (run ID: {}) is starting.", runId);
            timedStopFuture = null;
        }

        logger.info("Starting load emitters for {} targets for run ID: {}...", targets.size(), runId);
        payloadCache.clear();

        for (LoadTesterProperties.TargetEndpoint endpoint : targets) {
            if (endpoint.getName() == null || endpoint.getName().isBlank()) {
                logger.warn("Skipping target with missing or blank name for run ID: {}", runId);
                continue;
            }
            String targetName = endpoint.getName();
            if (activeEmitters.containsKey(targetName) && !activeEmitters.get(targetName).isDisposed()) {
                logger.warn("Emitter for target '{}' (run ID: {}) appears to be already active. Skipping.", targetName, runId);
                continue;
            }

            int desiredTps = endpoint.getDesiredTps();
            int throttleMs = endpoint.getThrottleIntervalMs();

            rateLimiterService.initializeBucket(targetName, throttleMs);

            HttpMethod method = HttpMethod.resolve(endpoint.getMethod().toUpperCase());
            if (method == null) {
                logger.error("Unsupported HTTP method '{}' for target '{}' (run ID: {}). Skipping.", endpoint.getMethod(), targetName, runId);
                continue;
            }
            if (endpoint.getPayloadPath() != null &&
                    (method == HttpMethod.POST || method == HttpMethod.PUT || method == HttpMethod.PATCH)) {
                try {
                    String body = payloadLoader.loadPayload(endpoint.getPayloadPath());
                    payloadCache.put(targetName, body);
                } catch (RuntimeException e) {
                    logger.error("Failed to load payload for target '{}' (run ID: {}) from path '{}'. Skipping. Error: {}",
                            targetName, runId, endpoint.getPayloadPath(), e.getMessage());
                    continue;
                }
            }

            Gauge.builder("loadtester.tps.desired", () -> endpoint.getDesiredTps())
                    .description("Desired transactions per second for the target")
                    .tag("target", targetName)
                    .register(meterRegistry);

            long intervalMs = (desiredTps <= 0) ? 1L : Math.max(1L, 1000L / desiredTps);

            Flux<Long> flux = Flux.interval(Duration.ofMillis(intervalMs))
                    .onBackpressureDrop(droppedTick -> {
                        logger.warn("[{}] (Run ID: {}) Backpressure applied, request tick {} dropped.", targetName, this.currentRunId.get(), droppedTick);
                        meterRegistry.counter("loadtester.requests.backpressure.dropped", "target", targetName).increment();
                    });

            Disposable emitterSubscription = flux.subscribe(tick -> {
                if (!emittersRunning.get()) {
                    Disposable d = activeEmitters.get(targetName);
                    if(d != null && !d.isDisposed()) {
                        d.dispose();
                    }
                    return;
                }
                if (!rateLimiterService.tryConsume(targetName)) {
                    logger.trace("[{}] (Run ID: {}) tick={}, rate-limited. Skipping", targetName, this.currentRunId.get(), tick);
                    meterRegistry.counter("loadtester.requests.throttled", "target", targetName).increment();
                    return;
                }

                meterRegistry.counter("loadtester.requests.initiated", "target", targetName ).increment();
                Timer.Sample requestTimerSample = Timer.start(meterRegistry);

                sendRequest(endpoint)
                        .doOnSuccess(status -> {
                            requestTimerSample.stop(meterRegistry.timer("loadtester.request.latency",
                                    Tags.of("target", targetName, "http_status", String.valueOf(status), "outcome", "success" )));
                            meterRegistry.counter("loadtester.requests.completed",
                                    Tags.of("target", targetName, "http_status", String.valueOf(status), "outcome", "success" )).increment();
                            meterRegistry.counter("loadtester.requests.by_status",
                                    Tags.of("target", targetName, "http_status", String.valueOf(status) )).increment();
                            logger.trace("[{}] (Run ID: {}) request #{} succeeded with status {}", targetName, this.currentRunId.get(), tick, status);
                        })
                        .doOnError(error -> {
                            String httpStatusStr = "CLIENT_ERROR";
                            String errorType = error.getClass().getSimpleName();

                            if (error instanceof WebClientResponseException) {
                                WebClientResponseException wcre = (WebClientResponseException) error;
                                httpStatusStr = String.valueOf(wcre.getRawStatusCode());
                                meterRegistry.counter("loadtester.requests.by_status",
                                        Tags.of("target", targetName, "http_status", httpStatusStr )).increment();
                            } else {
                                meterRegistry.counter("loadtester.requests.by_status",
                                        Tags.of("target", targetName, "http_status", "CLIENT_ERROR" )).increment();
                            }

                            requestTimerSample.stop(meterRegistry.timer("loadtester.request.latency",
                                    Tags.of("target", targetName, "http_status", httpStatusStr, "outcome", "failure" )));
                            meterRegistry.counter("loadtester.requests.completed",
                                    Tags.of("target", targetName, "http_status", httpStatusStr, "outcome", "failure", "error_type", errorType )).increment();
                            logger.warn("[{}] (Run ID: {}) request #{} failed. Status: {}, Error Type: {}, Message: {}", targetName, this.currentRunId.get(), tick, httpStatusStr, errorType, error.getMessage());
                        })
                        .subscribe(
                                status -> {},
                                error -> {}
                        );
            });

            activeEmitters.put(targetName, emitterSubscription);
            logger.info("Started emitter for target [{}] for run ID: {}", targetName, runId);
        }

        if (!activeEmitters.isEmpty()){
            emittersRunning.set(true);
            logger.info("All configured load emitters started successfully for run ID: {}.", runId);

            int runDurationMinutes = props.getRunDurationMinutes();
            if (runDurationMinutes > 0) {
                logger.info("Load test session (run ID: {}) scheduled to run for {} minutes.", runId, runDurationMinutes);
                final String capturedRunId = this.currentRunId.get(); // Capture for lambda
                timedStopFuture = timedStopScheduler.schedule(() -> {
                    logger.info("Configured run duration of {} minutes reached for run ID: {}. Automatically stopping emitters.", runDurationMinutes, capturedRunId);
                    stopEmitters();
                    if (props.getReporting().getShutdown().isEnabled()) { // Re-purpose shutdown report flag
                        logger.info("Generating summary report after timed session completion for run ID: {} (as reporting.shutdown.enabled is true).", capturedRunId);
                        summaryReportingService.generateAndLogSummaryReport(capturedRunId); // Pass capturedRunId
                    }
                }, runDurationMinutes, TimeUnit.MINUTES);
            } else {
                logger.info("Load test session (run ID: {}) configured to run indefinitely.", runId);
            }

        } else {
            logger.warn("No emitters were started for run ID: {}.", runId);
            emittersRunning.set(false);
            this.currentRunId.set(null); // Clear runId if nothing started
        }
    }

    private Mono<Integer> sendRequest(LoadTesterProperties.TargetEndpoint ep) {
        HttpMethod method = HttpMethod.resolve(ep.getMethod().toUpperCase());
        if (method == null) {
            return Mono.error(new IllegalArgumentException("Unsupported method: " + ep.getMethod()));
        }

        WebClient.RequestBodySpec requestSpec = webClient
                .method(method)
                .uri(ep.getUrl());

        if (ep.getHeaders() != null) {
            ep.getHeaders().forEach(requestSpec::header);
        }

        WebClient.RequestHeadersSpec<?> finalRequestSpec = requestSpec;

        if (payloadCache.containsKey(ep.getName()) &&
                (method == HttpMethod.POST || method == HttpMethod.PUT || method == HttpMethod.PATCH)) {
            String body = payloadCache.get(ep.getName());
            String contentType = (ep.getHeaders() != null && ep.getHeaders().get("Content-Type") != null)
                    ? ep.getHeaders().get("Content-Type")
                    : MediaType.APPLICATION_JSON_VALUE;
            finalRequestSpec = requestSpec.contentType(MediaType.parseMediaType(contentType)).bodyValue(body);
        } else if (ep.getPayloadPath() != null &&
                (method == HttpMethod.POST || method == HttpMethod.PUT || method == HttpMethod.PATCH) &&
                !payloadCache.containsKey(ep.getName())) {
            logger.error("Payload for {} was specified but is not in cache for run ID {}. Request will likely fail or be sent without body.", ep.getName(), this.currentRunId.get());
        }

        return ((WebClient.RequestHeadersSpec<?>) finalRequestSpec)
                .exchangeToMono(response -> {
                    int rawStatusCode = response.rawStatusCode();
                    return response.bodyToMono(String.class)
                            .defaultIfEmpty("")
                            .flatMap(bodyContent -> {
                                if (response.statusCode().isError()) {
                                    String errorBodySnippet = bodyContent.length() > 200 ? bodyContent.substring(0, 200) + "..." : bodyContent;
                                    logger.warn("Target '{}' (Run ID: {}) request failed with HTTP {}. Response body snippet: {}", ep.getName(), this.currentRunId.get(), rawStatusCode, errorBodySnippet);
                                    return Mono.error(WebClientResponseException.create(
                                            rawStatusCode,
                                            HttpStatus.resolve(rawStatusCode) != null ? HttpStatus.resolve(rawStatusCode).getReasonPhrase() : "Unknown Status",
                                            response.headers().asHttpHeaders(),
                                            bodyContent.getBytes(),
                                            null
                                    ));
                                }
                                return Mono.just(rawStatusCode);
                            });
                });
    }

    public synchronized void stopEmitters() {
        final String runIdForStop = this.currentRunId.get(); // Get current run ID for logging
        if (!emittersRunning.get() && activeEmitters.isEmpty() && (timedStopFuture == null || timedStopFuture.isDone())) {
            logger.info("Load emitters (for run ID: {}) are already stopped or were never started. No action taken.", runIdForStop);
            return;
        }

        logger.info("Attempting to stop all active load emitters (run ID: {}, currently {} emitters)...", runIdForStop, activeEmitters.size());
        emittersRunning.set(false);

        if (timedStopFuture != null && !timedStopFuture.isDone()) {
            timedStopFuture.cancel(true);
            logger.info("Cancelled any pending scheduled timed stop of emitters for run ID: {}.", runIdForStop);
            timedStopFuture = null;
        }

        activeEmitters.forEach((name, emitter) -> {
            if (emitter != null && !emitter.isDisposed()) {
                emitter.dispose();
                logger.info("Disposed emitter for target [{}] (run ID: {})", name, runIdForStop);
            }
        });
        activeEmitters.clear();

        logger.info("All load emitters for run ID: {} have been commanded to stop.", runIdForStop);
    }


    public String getCurrentRunId() {
        return this.currentRunId.get();
    }

    /**
     * Call this method after a run is fully completed and reported to clear its context.
     */
    public synchronized void finalizeRunSession() {
        String oldRunId = this.currentRunId.getAndSet(null);
        if (oldRunId != null) {
            logger.info("Finalized session for run ID: {}", oldRunId);
        }
        if (emittersRunning.get() || !activeEmitters.isEmpty()) {
            logger.warn("Finalizing run session, but emitters appear to be active. This might indicate an issue in the stop sequence.");
            stopEmitters();
        }
    }


    public boolean areEmittersRunning() {
        return emittersRunning.get();
    }

    @Override
    public void destroy() throws Exception {
        logger.info("Shutting down LoadEmitterService due to application context destruction.");
        stopEmitters();

        if (timedStopScheduler != null && !timedStopScheduler.isShutdown()) {
            logger.info("Shutting down the timedStopScheduler...");
            timedStopScheduler.shutdown();
            try {
                if (!timedStopScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    logger.warn("TimedStopScheduler did not terminate in 5 seconds, forcing shutdown...");
                    timedStopScheduler.shutdownNow();
                    if (!timedStopScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                        logger.error("TimedStopScheduler did not terminate even after forced shutdown.");
                    }
                }
            } catch (InterruptedException ie) {
                logger.error("Interrupted while waiting for TimedStopScheduler to terminate. Forcing shutdown.", ie);
                timedStopScheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
            logger.info("TimedStopScheduler shutdown complete.");
        }
    }
}
