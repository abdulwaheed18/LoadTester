package com.example.loadtester.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated; // For validation if you add JSR 303

import javax.validation.constraints.Min; // Example validation
import javax.validation.constraints.NotBlank; // Example validation
import java.util.List;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "loadtester")
@Validated // Enable validation if you add constraints
public class LoadTesterProperties {
    private List<TargetEndpoint> targets;
    private Reporting reporting = new Reporting(); // Initialize with default values

    public List<TargetEndpoint> getTargets() {
        return targets;
    }

    public void setTargets(List<TargetEndpoint> targets) {
        this.targets = targets;
    }

    public Reporting getReporting() {
        return reporting;
    }

    public void setReporting(Reporting reporting) {
        this.reporting = reporting;
    }

    public static class TargetEndpoint {
        @NotBlank(message = "Target name must not be blank")
        private String name;
        @NotBlank(message = "Target URL must not be blank")
        private String url;
        @NotBlank(message = "Target method must not be blank")
        private String method;
        private Map<String, String> headers;
        private String payloadPath; // Can be null for GET, DELETE etc.
        @Min(value = 0, message = "Desired TPS must be non-negative. Use 0 for max effort without specific TPS target.")
        private int desiredTps;
        @Min(value = 0, message = "Throttle interval must be non-negative. Use 0 for no specific throttle.")
        private int throttleIntervalMs;

        // Getters and Setters
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
        public String toString() {
            return "TargetEndpoint{" +
                    "name='" + name + '\'' +
                    ", url='" + url + '\'' +
                    ", method='" + method + '\'' +
                    ", desiredTps=" + desiredTps +
                    ", throttleIntervalMs=" + throttleIntervalMs +
                    '}';
        }
    }

    public static class Reporting {
        private Periodic periodic = new Periodic();
        private Shutdown shutdown = new Shutdown();

        public Periodic getPeriodic() { return periodic; }
        public void setPeriodic(Periodic periodic) { this.periodic = periodic; }
        public Shutdown getShutdown() { return shutdown; }
        public void setShutdown(Shutdown shutdown) { this.shutdown = shutdown; }

        public static class Periodic {
            private boolean enabled = true;
            private long summaryIntervalMs = 60000; // 60 seconds

            public boolean isEnabled() { return enabled; }
            public void setEnabled(boolean enabled) { this.enabled = enabled; }
            public long getSummaryIntervalMs() { return summaryIntervalMs; }
            public void setSummaryIntervalMs(long summaryIntervalMs) { this.summaryIntervalMs = summaryIntervalMs; }
        }

        public static class Shutdown {
            private boolean enabled = true;

            public boolean isEnabled() { return enabled; }
            public void setEnabled(boolean enabled) { this.enabled = enabled; }
        }
    }
}