package com.example.loadtester.service;

import com.example.loadtester.config.LoadTesterProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
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
    private final Map<String, Disposable> activeEmitters = new HashMap<>();

    public LoadEmitterService(
            LoadTesterProperties props,
            RateLimiterService rateLimiterService,
            PayloadLoader payloadLoader,
            WebClient.Builder webClientBuilder
    ) {
        this.props = props;
        this.rateLimiterService = rateLimiterService;
        this.payloadLoader = payloadLoader;
        this.webClient = webClientBuilder.build();
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
            String name = endpoint.getName();
            int tps = endpoint.getDesiredTps();
            int throttleMs = endpoint.getThrottleIntervalMs();
            rateLimiterService.initializeBucket(name, throttleMs);

            long intervalMs = (tps <= 0) ? 1000L : (1000L / tps);

            Flux<Long> flux = Flux.interval(Duration.ofMillis(intervalMs))
                    .onBackpressureDrop();

            Disposable emitter = flux.subscribe(tick -> {
                if (!rateLimiterService.tryConsume(name)) {
                    logger.debug("[{}] tick={}, rate-limited, skipping", name, tick);
                    return;
                }
                sendRequest(endpoint)
                        .doOnSuccess(status ->
                                logger.debug("[{}] request #{} succeeded with status {}", name, tick, status))
                        .doOnError(err ->
                                logger.warn("[{}] request #{} failed", name, tick, err))
                        .subscribe();
            });

            activeEmitters.put(name, emitter);
            logger.info("Started emitter for [{}] @ {} TPS (interval={} ms, throttle={} ms)",
                    name, tps, intervalMs, throttleMs);
        }
    }

    private Mono<Integer> sendRequest(LoadTesterProperties.TargetEndpoint ep) {
        HttpMethod method = HttpMethod.resolve(ep.getMethod());
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

        if (ep.getPayloadPath() != null && (method == HttpMethod.POST || method == HttpMethod.PUT || method == HttpMethod.PATCH)) {
            String body = payloadLoader.loadPayload(ep.getPayloadPath());
            requestSpec = (WebClient.RequestBodySpec) requestSpec
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body);
        }

        return requestSpec
                .exchangeToMono(response ->
                        response.bodyToMono(String.class)
                                .defaultIfEmpty("")
                                .flatMap(body -> {
                                    int status = response.rawStatusCode();
                                    if (response.statusCode().is2xxSuccessful()) {
                                        return Mono.just(status);
                                    } else {
                                        logger.warn("Backend returned {} with body: {}", status, body);
                                        return Mono.error(new RuntimeException("HTTP " + status + ": " + body));
                                    }
                                })
                );
    }
}