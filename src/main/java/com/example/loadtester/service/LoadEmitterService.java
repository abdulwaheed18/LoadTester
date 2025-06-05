// src/main/java/com/example/loadtester/service/LoadEmitterService.java
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
import org.springframework.context.annotation.Lazy;
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
public class LoadEmitterService implements DisposableBean {
    private static final Logger logger = LoggerFactory.getLogger(LoadEmitterService.class);

    private final RateLimiterService rateLimiterService;
    private final PayloadLoader payloadLoader;
    private final WebClient webClient;
    private final MeterRegistry meterRegistry;
    private final SummaryReportingService summaryReportingService;

    private final Map<String, Disposable> activeEmitters = new ConcurrentHashMap<>();
    private final Map<String, String> payloadCache = new ConcurrentHashMap<>();
    private final AtomicBoolean emittersRunning = new AtomicBoolean(false);
    private final AtomicReference<String> currentRunId = new AtomicReference<>(null);
    private final AtomicReference<String> currentConfigName = new AtomicReference<>("N/A");
    private final AtomicReference<LoadTesterProperties> activeRunProperties = new AtomicReference<>();

    private final ScheduledExecutorService timedStopScheduler;
    private ScheduledFuture<?> timedStopFuture;

    @Autowired
    public LoadEmitterService(
            RateLimiterService rateLimiterService,
            PayloadLoader payloadLoader,
            WebClient.Builder webClientBuilder,
            MeterRegistry meterRegistry,
            @Lazy SummaryReportingService summaryReportingService
    ) {
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
     * Starts the load generation emitters using the provided LoadTesterProperties.
     * @param runId The unique identifier for this load test run.
     * @param propertiesToUse The configuration properties to use for this run.
     * @param configName The display name of the configuration being used.
     */
    public synchronized void startEmitters(String runId, LoadTesterProperties propertiesToUse, String configName) {
        if (emittersRunning.get()) {
            logger.info("Load emitters are already running under run ID: {}. Cannot start a new session.", this.currentRunId.get());
            return;
        }
        if (runId == null || runId.trim().isEmpty()) {
            logger.error("Run ID cannot be null or empty. Aborting startEmitters.");
            return;
        }
        if (propertiesToUse == null) {
            logger.error("LoadTesterProperties cannot be null. Aborting startEmitters for run ID: {}", runId);
            return;
        }
        this.currentRunId.set(runId);
        this.activeRunProperties.set(propertiesToUse);
        this.currentConfigName.set(configName != null ? configName : "Custom/Unknown");

        logger.info("Attempting to start load emitters for run ID: {} using configuration: {}", runId, this.currentConfigName.get());

        List<LoadTesterProperties.TargetEndpoint> targets = propertiesToUse.getTargets();
        if (targets == null || targets.isEmpty()) {
            logger.warn("No targets configured for run ID: {}. Cannot start emitters.", runId);
            finalizeRunSessionState(); // Clear run state as nothing will start
            return;
        }

        if (timedStopFuture != null && !timedStopFuture.isDone()) {
            timedStopFuture.cancel(false);
            logger.debug("Cancelled previous timed stop task as new session (run ID: {}) is starting.", runId);
            timedStopFuture = null; // Reset future
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
            if (endpoint.getPayloadPath() != null && !endpoint.getPayloadPath().trim().isEmpty() &&
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

            // Ensure currentRunId.get() is not null before using it in tags
            String currentRunIdValue = this.currentRunId.get();
            if (currentRunIdValue == null) {
                logger.error("Critical error: currentRunId is null during emitter setup for target {}. Aborting this target.", targetName);
                continue; // Skip this target if runId is not set.
            }


            Gauge.builder("loadtester.tps.desired", () -> endpoint.getDesiredTps())
                    .description("Desired transactions per second for the target")
                    .tag("target", targetName)
                    .tag("runId", currentRunIdValue)
                    .register(meterRegistry);

            long intervalMs = (desiredTps <= 0) ? 1L : Math.max(1L, 1000L / desiredTps);

            Flux<Long> flux = Flux.interval(Duration.ofMillis(intervalMs))
                    .onBackpressureDrop(droppedTick -> {
                        logger.warn("[{}] (Run ID: {}) Backpressure applied, request tick {} dropped.", targetName, currentRunIdValue, droppedTick);
                        meterRegistry.counter("loadtester.requests.backpressure.dropped", "target", targetName, "runId", currentRunIdValue).increment();
                    });

            Disposable emitterSubscription = flux.subscribe(tick -> {
                String activeRunId = this.currentRunId.get(); // Re-fetch in case it changed, though unlikely in this path
                if (!emittersRunning.get() || activeRunId == null) { // Check global flag and ensure runId is still set
                    Disposable d = activeEmitters.get(targetName);
                    if(d != null && !d.isDisposed()) {
                        d.dispose();
                    }
                    return;
                }
                if (!rateLimiterService.tryConsume(targetName)) {
                    logger.trace("[{}] (Run ID: {}) tick={}, rate-limited. Skipping", targetName, activeRunId, tick);
                    meterRegistry.counter("loadtester.requests.throttled", "target", targetName, "runId", activeRunId).increment();
                    return;
                }

                meterRegistry.counter("loadtester.requests.initiated", "target", targetName, "runId", activeRunId).increment();
                Timer.Sample requestTimerSample = Timer.start(meterRegistry);

                sendRequest(endpoint)
                        .doOnSuccess(status -> {
                            requestTimerSample.stop(meterRegistry.timer("loadtester.request.latency",
                                    Tags.of("target", targetName, "http_status", String.valueOf(status), "outcome", "success", "runId", activeRunId )));
                            meterRegistry.counter("loadtester.requests.completed",
                                    Tags.of("target", targetName, "http_status", String.valueOf(status), "outcome", "success", "runId", activeRunId)).increment();
                            meterRegistry.counter("loadtester.requests.by_status",
                                    Tags.of("target", targetName, "http_status", String.valueOf(status), "runId", activeRunId )).increment();
                            logger.trace("[{}] (Run ID: {}) request #{} succeeded with status {}", targetName, activeRunId, tick, status);
                        })
                        .doOnError(error -> {
                            String httpStatusStr = "CLIENT_ERROR";
                            String errorType = error.getClass().getSimpleName();

                            if (error instanceof WebClientResponseException) {
                                WebClientResponseException wcre = (WebClientResponseException) error;
                                httpStatusStr = String.valueOf(wcre.getRawStatusCode());
                                meterRegistry.counter("loadtester.requests.by_status",
                                        Tags.of("target", targetName, "http_status", httpStatusStr, "runId", activeRunId )).increment();
                            } else {
                                meterRegistry.counter("loadtester.requests.by_status",
                                        Tags.of("target", targetName, "http_status", "CLIENT_ERROR", "runId", activeRunId )).increment();
                            }

                            requestTimerSample.stop(meterRegistry.timer("loadtester.request.latency",
                                    Tags.of("target", targetName, "http_status", httpStatusStr, "outcome", "failure", "runId", activeRunId )));
                            meterRegistry.counter("loadtester.requests.completed",
                                    Tags.of("target", targetName, "http_status", httpStatusStr, "outcome", "failure", "error_type", errorType, "runId", activeRunId)).increment();
                            logger.warn("[{}] (Run ID: {}) request #{} failed. Status: {}, Error Type: {}, Message: {}", targetName, activeRunId, tick, httpStatusStr, errorType, error.getMessage());
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

            int runDurationMinutes = propertiesToUse.getRunDurationMinutes();
            if (runDurationMinutes > 0) {
                logger.info("Load test session (run ID: {}) scheduled to run for {} minutes.", runId, runDurationMinutes);
                final String capturedRunId = this.currentRunId.get();
                timedStopFuture = timedStopScheduler.schedule(() -> {
                    logger.info("Configured run duration of {} minutes reached for run ID: {}. Automatically stopping emitters.", runDurationMinutes, capturedRunId);
                    stopEmitters();
                    LoadTesterProperties currentProps = activeRunProperties.get(); // Get props for the run that just ended
                    if (currentProps != null && currentProps.getReporting() != null && currentProps.getReporting().getShutdown().isEnabled()) {
                        logger.info("Generating summary report after timed session completion for run ID: {} (as reporting.shutdown.enabled is true).", capturedRunId);
                        summaryReportingService.generateAndLogSummaryReport(capturedRunId);
                    }
                    finalizeRunSessionState(); // Finalize state after report
                }, runDurationMinutes, TimeUnit.MINUTES);
            } else {
                logger.info("Load test session (run ID: {}) configured to run indefinitely.", runId);
            }
        } else {
            logger.warn("No emitters were started for run ID: {}.", runId);
            finalizeRunSessionState(); // Clear run state if nothing started
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
            String contentTypeHeader = ep.getHeaders() != null ? ep.getHeaders().get("Content-Type") : null;
            MediaType mediaType = MediaType.APPLICATION_JSON; // Default
            if (contentTypeHeader != null) {
                try {
                    mediaType = MediaType.parseMediaType(contentTypeHeader);
                } catch (Exception e) {
                    logger.warn("Invalid Content-Type header '{}' for target {}. Defaulting to application/json.", contentTypeHeader, ep.getName());
                }
            }
            finalRequestSpec = requestSpec.contentType(mediaType).bodyValue(body);
        } else if (ep.getPayloadPath() != null && !ep.getPayloadPath().trim().isEmpty() &&
                (method == HttpMethod.POST || method == HttpMethod.PUT || method == HttpMethod.PATCH) &&
                !payloadCache.containsKey(ep.getName())) {
            logger.error("Payload for {} was specified but is not in cache for run ID {}. Request will be sent without body or may fail.", ep.getName(), this.currentRunId.get());
        }

        return ((WebClient.RequestHeadersSpec<?>) finalRequestSpec)
                .exchangeToMono(response -> {
                    int rawStatusCode = response.rawStatusCode();
                    return response.bodyToMono(String.class)
                            .defaultIfEmpty("")
                            .flatMap(bodyContent -> {
                                if (response.statusCode().isError()) {
                                    String errorBodySnippet = bodyContent.length() > 200 ? bodyContent.substring(0, 200) + "..." : bodyContent;
                                    logger.debug("Target '{}' (Run ID: {}) request failed with HTTP {}. Response body snippet: {}", ep.getName(), this.currentRunId.get(), rawStatusCode, errorBodySnippet);
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
        final String runIdForStop = this.currentRunId.get();
        if (!emittersRunning.get() && activeEmitters.isEmpty() && (timedStopFuture == null || timedStopFuture.isDone())) {
            logger.info("Load emitters (for run ID: {}) are already stopped or were never started. No action taken.", runIdForStop);
            return;
        }

        logger.info("Attempting to stop all active load emitters (run ID: {}, using config: {}, currently {} emitters)...",
                runIdForStop, this.currentConfigName.get(), activeEmitters.size());
        emittersRunning.set(false);

        if (timedStopFuture != null && !timedStopFuture.isDone()) {
            timedStopFuture.cancel(true);
            logger.info("Cancelled any pending scheduled timed stop of emitters for run ID: {}.", runIdForStop);
            timedStopFuture = null; // Reset future
        }

        activeEmitters.forEach((name, emitter) -> {
            if (emitter != null && !emitter.isDisposed()) {
                emitter.dispose();
                logger.info("Disposed emitter for target [{}] (run ID: {})", name, runIdForStop);
            }
        });
        activeEmitters.clear();
        logger.info("All load emitters for run ID: {} have been commanded to stop.", runIdForStop);
        // Note: finalizeRunSessionState() which clears currentRunId, activeRunProperties etc.
        // should be called by the controller *after* any final report generation.
    }

    public String getCurrentRunId() {
        return this.currentRunId.get();
    }
    public String getCurrentConfigName() { return this.currentConfigName.get(); }
    public LoadTesterProperties getActiveRunProperties() { return this.activeRunProperties.get(); }


    // Renamed to avoid confusion with controller's finalizeRunSession
    public synchronized void finalizeRunSessionState() {
        String oldRunId = this.currentRunId.getAndSet(null);
        this.activeRunProperties.set(null);
        this.currentConfigName.set("N/A");
        this.payloadCache.clear();
        this.emittersRunning.set(false); // Ensure this is false
        if (timedStopFuture != null && !timedStopFuture.isDone()){
            timedStopFuture.cancel(true); // Ensure timed stop is also cancelled
            timedStopFuture = null;
        }
        if (oldRunId != null) {
            logger.info("Finalized session state for run ID: {}", oldRunId);
        }
        if (!activeEmitters.isEmpty()) { // Should be empty if stopEmitters worked
            logger.warn("Finalizing run session state, but activeEmitters map is not empty. Clearing it now.");
            activeEmitters.forEach((name, emitter) -> {
                if (emitter != null && !emitter.isDisposed()) emitter.dispose();
            });
            activeEmitters.clear();
        }
    }


    public boolean areEmittersRunning() {
        return emittersRunning.get();
    }

    @Override
    public void destroy() throws Exception {
        logger.info("Shutting down LoadEmitterService due to application context destruction.");
        if (emittersRunning.get()) { // If a test is somehow still marked as running
            logger.warn("Emitters were marked as running during application destruction. Forcing stop and finalization.");
            stopEmitters();
            // A final report might be missed here if shutdown reporting relied on the normal flow.
            // The @PreDestroy in SummaryReportingService should ideally handle its own final report.
        }
        finalizeRunSessionState(); // Ensure all state is cleared

        if (timedStopScheduler != null && !timedStopScheduler.isShutdown()) {
            logger.info("Shutting down the timedStopScheduler...");
            timedStopScheduler.shutdown();
            try {
                if (!timedStopScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    logger.warn("TimedStopScheduler did not terminate in 5 seconds, forcing shutdown...");
                    timedStopScheduler.shutdownNow();
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
