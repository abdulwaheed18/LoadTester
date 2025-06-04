package com.example.loadtester.service;

import com.example.loadtester.config.LoadTesterProperties;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import javax.annotation.PostConstruct;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class LoadEmitterService {
    private static final Logger logger = LoggerFactory.getLogger(LoadEmitterService.class);

    private final LoadTesterProperties props;
    private final RateLimiterService rateLimiterService;
    private final PayloadLoader payloadLoader;
    private final WebClient webClient;
    private final MeterRegistry meterRegistry;

    private final Map<String, Disposable> activeEmitters = new HashMap<>();
    private final Map<String, String> payloadCache = new HashMap<>();


    public LoadEmitterService(
            LoadTesterProperties props,
            RateLimiterService rateLimiterService,
            PayloadLoader payloadLoader,
            WebClient.Builder webClientBuilder,
            MeterRegistry meterRegistry
    ) {
        this.props = props;
        this.rateLimiterService = rateLimiterService;
        this.payloadLoader = payloadLoader;
        this.webClient = webClientBuilder.build();
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    public void startAllEmitters() {
        List<LoadTesterProperties.TargetEndpoint> targets = props.getTargets();
        if (targets == null || targets.isEmpty()) {
            logger.warn("No targets configured. Exiting emitter startup.");
            return;
        }

        for (LoadTesterProperties.TargetEndpoint endpoint : targets) {
            if (endpoint.getName() == null || endpoint.getName().isBlank()) {
                logger.warn("Skipping target with missing or blank name: {}", endpoint);
                continue;
            }
            String targetName = endpoint.getName();
            int desiredTps = endpoint.getDesiredTps();
            int throttleMs = endpoint.getThrottleIntervalMs();

            rateLimiterService.initializeBucket(targetName, throttleMs);

            HttpMethod method = HttpMethod.resolve(endpoint.getMethod().toUpperCase());
            if (method == null) {
                logger.error("Unsupported HTTP method '{}' for target '{}'. Skipping.", endpoint.getMethod(), targetName);
                continue;
            }
            if (endpoint.getPayloadPath() != null &&
                    (method == HttpMethod.POST || method == HttpMethod.PUT || method == HttpMethod.PATCH)) {
                try {
                    String body = payloadLoader.loadPayload(endpoint.getPayloadPath());
                    payloadCache.put(targetName, body);
                } catch (RuntimeException e) {
                    logger.error("Failed to load payload for target '{}' from path '{}'. Skipping emitter. Error: {}",
                            targetName, endpoint.getPayloadPath(), e.getMessage());
                    continue;
                }
            }

            Gauge.builder("loadtester.tps.desired", () -> desiredTps)
                    .description("Desired transactions per second for the target")
                    .tag("target", targetName)
                    .register(meterRegistry);

            long intervalMs = (desiredTps <= 0) ? 1L : Math.max(1L, 1000L / desiredTps);

            Flux<Long> flux = Flux.interval(Duration.ofMillis(intervalMs))
                    .onBackpressureDrop(droppedTick -> {
                        logger.warn("[{}] Backpressure applied, request tick {} dropped.", targetName, droppedTick);
                        meterRegistry.counter("loadtester.requests.backpressure.dropped", "target", targetName).increment();
                    });

            Disposable emitter = flux.subscribe(tick -> {
                if (!rateLimiterService.tryConsume(targetName)) {
                    logger.debug("[{}] tick={}, rate-limited, skipping", targetName, tick);
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
                            // New: Counter for specific status codes
                            meterRegistry.counter("loadtester.requests.by_status",
                                    Tags.of("target", targetName, "http_status", String.valueOf(status))).increment();
                            logger.debug("[{}] request #{} succeeded with status {}", targetName, tick, status);
                        })
                        .doOnError(error -> {
                            String httpStatusStr = "error"; // Default for non-HTTP errors
                            String errorType = error.getClass().getSimpleName();

                            if (error instanceof WebClientResponseException) {
                                WebClientResponseException wcre = (WebClientResponseException) error;
                                httpStatusStr = String.valueOf(wcre.getRawStatusCode());
                                // New: Counter for specific error status codes
                                meterRegistry.counter("loadtester.requests.by_status",
                                        Tags.of("target", targetName, "http_status", httpStatusStr)).increment();
                            } else {
                                // For non-WebClientResponseException errors, still count them under a generic error status if needed
                                meterRegistry.counter("loadtester.requests.by_status",
                                        Tags.of("target", targetName, "http_status", "CLIENT_ERROR")).increment();
                            }

                            requestTimerSample.stop(meterRegistry.timer("loadtester.request.latency",
                                    Tags.of("target", targetName, "http_status", httpStatusStr, "outcome", "failure")));
                            meterRegistry.counter("loadtester.requests.completed",
                                    Tags.of("target", targetName, "http_status", httpStatusStr, "outcome", "failure", "error_type", errorType)).increment();
                            logger.warn("[{}] request #{} failed with status {} and error type {}", targetName, tick, httpStatusStr, errorType, error.getMessage());
                        })
                        .subscribe(
                                status -> {},
                                error -> {}
                        );
            });

            activeEmitters.put(targetName, emitter);
            logger.info("Started emitter for [{}] @ {} TPS (interval={} ms, throttle={} ms)",
                    targetName, desiredTps == 0 ? "max_effort" : desiredTps, intervalMs, throttleMs);
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
            for (Map.Entry<String, String> h : ep.getHeaders().entrySet()) {
                requestSpec = requestSpec.header(h.getKey(), h.getValue());
            }
        }

        WebClient.RequestHeadersSpec<?> finalRequestSpec = requestSpec;

        if (ep.getPayloadPath() != null && (method == HttpMethod.POST || method == HttpMethod.PUT || method == HttpMethod.PATCH)) {
            String body = payloadCache.get(ep.getName());
            if (body == null) {
                try {
                    body = payloadLoader.loadPayload(ep.getPayloadPath());
                    payloadCache.put(ep.getName(), body);
                } catch (RuntimeException e) {
                    logger.error("Failed to load payload for {} during request sending: {}", ep.getName(), e.getMessage());
                    return Mono.error(e);
                }
            }
            String contentType = ep.getHeaders() != null ? ep.getHeaders().getOrDefault("Content-Type", MediaType.APPLICATION_JSON_VALUE) : MediaType.APPLICATION_JSON_VALUE;
            finalRequestSpec = requestSpec.contentType(MediaType.parseMediaType(contentType)).bodyValue(body);
        }

        return ((WebClient.RequestHeadersSpec<?>) finalRequestSpec)
                .exchangeToMono(response -> {
                    int rawStatusCode = response.rawStatusCode();
                    return response.bodyToMono(String.class)
                            .defaultIfEmpty("")
                            .flatMap(bodyContent -> {
                                if (response.statusCode().isError()) {
                                    // Log first few chars of error body for debugging
                                    String errorBodySnippet = bodyContent.length() > 500 ? bodyContent.substring(0, 500) + "..." : bodyContent;
                                    logger.warn("Target '{}' returned HTTP {} with body snippet: {}", ep.getName(), rawStatusCode, errorBodySnippet);
                                    return Mono.error(WebClientResponseException.create(rawStatusCode,
                                            HttpStatus.resolve(rawStatusCode) != null ? HttpStatus.resolve(rawStatusCode).getReasonPhrase() : "Unknown Status",
                                            response.headers().asHttpHeaders(),
                                            bodyContent.getBytes(), // Full body for the exception
                                            null));
                                }
                                return Mono.just(rawStatusCode);
                            });
                });
    }

    public void stopAllEmitters() {
        activeEmitters.forEach((name, emitter) -> {
            if (!emitter.isDisposed()) {
                emitter.dispose();
                logger.info("Stopped emitter for [{}]", name);
            }
        });
        activeEmitters.clear();
    }
}
