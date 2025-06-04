package com.example.loadtester.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "loadtester")
public class LoadTesterProperties {
    private List<TargetEndpoint> targets;

    public List<TargetEndpoint> getTargets() {
        return targets;
    }

    public void setTargets(List<TargetEndpoint> targets) {
        this.targets = targets;
    }

    public static class TargetEndpoint {
        private String name;
        private String url;
        private String method;
        private Map<String, String> headers;
        private String payloadPath;
        private int desiredTps;
        private int throttleIntervalMs;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getMethod() {
            return method;
        }

        public void setMethod(String method) {
            this.method = method;
        }

        public Map<String, String> getHeaders() {
            return headers;
        }

        public void setHeaders(Map<String, String> headers) {
            this.headers = headers;
        }

        public String getPayloadPath() {
            return payloadPath;
        }

        public void setPayloadPath(String payloadPath) {
            this.payloadPath = payloadPath;
        }

        public int getDesiredTps() {
            return desiredTps;
        }

        public void setDesiredTps(int desiredTps) {
            this.desiredTps = desiredTps;
        }

        public int getThrottleIntervalMs() {
            return throttleIntervalMs;
        }

        public void setThrottleIntervalMs(int throttleIntervalMs) {
            this.throttleIntervalMs = throttleIntervalMs;
        }
    }
}
