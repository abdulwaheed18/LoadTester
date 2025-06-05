// src/main/java/com/example/loadtester/model/TestConfiguration.java
package com.example.loadtester.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Represents a user-defined load test configuration.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class TestConfiguration {
    private String id;
    private String name;
    private String description;
    private int runDurationMinutes = 0;

    private List<TargetEndpointConfig> targets;

    public TestConfiguration() {
        if (this.id == null) {
            this.id = UUID.randomUUID().toString();
        }
    }

    public TestConfiguration(String name, String description, int runDurationMinutes, List<TargetEndpointConfig> targets) {
        this();
        this.name = name;
        this.description = description;
        this.runDurationMinutes = runDurationMinutes;
        this.targets = targets;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = (id == null || id.trim().isEmpty()) ? UUID.randomUUID().toString() : id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public int getRunDurationMinutes() {
        return runDurationMinutes;
    }

    public void setRunDurationMinutes(int runDurationMinutes) {
        this.runDurationMinutes = runDurationMinutes;
    }

    public List<TargetEndpointConfig> getTargets() {
        return targets;
    }

    public void setTargets(List<TargetEndpointConfig> targets) {
        this.targets = targets;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TargetEndpointConfig {
        private String name;
        private String url;
        private String method;
        private Map<String, String> headers;
        private String payloadPath;
        private int desiredTps;
        private int throttleIntervalMs;

        public TargetEndpointConfig() {}

        public TargetEndpointConfig(String name, String url, String method, Map<String, String> headers, String payloadPath, int desiredTps, int throttleIntervalMs) {
            this.name = name;
            this.url = url;
            this.method = method;
            this.headers = headers;
            this.payloadPath = payloadPath;
            this.desiredTps = desiredTps;
            this.throttleIntervalMs = throttleIntervalMs;
        }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getMethod() { return method; }
        public void setMethod(String method) { this.method = method; }
        public Map<String, String> getHeaders() { return headers; }
        public void setHeaders(Map<String, String> headers) { this.headers = headers; }
        public String getPayloadPath() { return payloadPath; }
        public void setPayloadPath(String payloadPath) { this.payloadPath = payloadPath; }
        public int getDesiredTps() { return desiredTps; }
        public void setDesiredTps(int desiredTps) { this.desiredTps = desiredTps; }
        public int getThrottleIntervalMs() { return throttleIntervalMs; }
        public void setThrottleIntervalMs(int throttleIntervalMs) { this.throttleIntervalMs = throttleIntervalMs; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            TargetEndpointConfig that = (TargetEndpointConfig) o;
            return desiredTps == that.desiredTps && throttleIntervalMs == that.throttleIntervalMs && Objects.equals(name, that.name) && Objects.equals(url, that.url) && Objects.equals(method, that.method) && Objects.equals(headers, that.headers) && Objects.equals(payloadPath, that.payloadPath);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, url, method, headers, payloadPath, desiredTps, throttleIntervalMs);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TestConfiguration that = (TestConfiguration) o;
        return runDurationMinutes == that.runDurationMinutes && Objects.equals(id, that.id) && Objects.equals(name, that.name) && Objects.equals(description, that.description) && Objects.equals(targets, that.targets);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, name, description, runDurationMinutes, targets);
    }
}
