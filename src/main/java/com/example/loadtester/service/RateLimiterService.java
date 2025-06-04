package com.example.loadtester.service;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Bucket4j;
import io.github.bucket4j.Refill;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class RateLimiterService {
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public void initializeBucket(String endpointName, int throttleIntervalMs) {
        if (endpointName == null || endpointName.isBlank()) {
            // Skip invalid endpointName
            return;
        }
        if (throttleIntervalMs <= 0) {
            // No throttle configured: do not put any entry, so get() returns null
            return;
        } else {
            Bandwidth limit = Bandwidth.classic(
                    1,
                    Refill.intervally(1, Duration.ofMillis(throttleIntervalMs))
            );
            Bucket bucket = Bucket4j.builder()
                    .addLimit(limit)
                    .build();
            buckets.put(endpointName, bucket);
        }
    }

    public boolean tryConsume(String endpointName) {
        if (endpointName == null || endpointName.isBlank()) {
            return true; // no rate limit for unknown endpoint
        }
        Bucket bucket = buckets.get(endpointName);
        if (bucket == null) {
            return true; // no bucket means no throttle
        }
        return bucket.tryConsume(1);
    }
}