// src/main/java/com/example/loadtester/config/LoadTesterProperties.java
package com.example.loadtester.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "loadtester")
@Validated
public class LoadTesterProperties {

    @Min(value = 0, message = "Run duration must be 0 (indefinite) or positive.")
    private int runDurationSeconds = 0; // Default to run indefinitely

    private List<TargetEndpoint> targets;
    private Reporting reporting = new Reporting();
    private Http http = new Http(); // New HTTP configuration section

    public int getRunDurationSeconds() {
        return runDurationSeconds;
    }

    public void setRunDurationSeconds(int runDurationSeconds) {
        this.runDurationSeconds = runDurationSeconds;
    }

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

    public Http getHttp() {
        return http;
    }

    public void setHttp(Http http) {
        this.http = http;
    }

    public static class TargetEndpoint {
        @NotBlank(message = "Target name must not be blank")
        private String name;
        @NotBlank(message = "Target URL must not be blank")
        private String url;
        @NotBlank(message = "Target method must not be blank")
        private String method;
        private Map<String, String> headers;
        private String payloadPath;
        @Min(value = 0, message = "Desired TPS must be non-negative. Use 0 for max effort without specific TPS target.")
        private int desiredTps;
        @Min(value = 0, message = "Throttle interval must be non-negative. Use 0 for no specific throttle.")
        private int throttleIntervalMs;
        // private boolean ignoreSslErrors = false; // Per-target SSL ignore - more complex, for future consideration

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
        // public boolean isIgnoreSslErrors() { return ignoreSslErrors; }
        // public void setIgnoreSslErrors(boolean ignoreSslErrors) { this.ignoreSslErrors = ignoreSslErrors; }


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
        private Html html = new Html();
        private History history = new History();
        private Configurations configurations = new Configurations(); // Keep user configs distinct from HTTP client config


        public Periodic getPeriodic() { return periodic; }
        public void setPeriodic(Periodic periodic) { this.periodic = periodic; }
        public Shutdown getShutdown() { return shutdown; }
        public void setShutdown(Shutdown shutdown) { this.shutdown = shutdown; }
        public Html getHtml() { return html; }
        public void setHtml(Html html) { this.html = html; }
        public History getHistory() { return history; }
        public void setHistory(History history) { this.history = history; }
        public Configurations getConfigurations() { return configurations; }
        public void setConfigurations(Configurations configurations) { this.configurations = configurations;}


        public static class Periodic {
            private boolean enabled = true;
            private long summaryIntervalMs = 60000;

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

        public static class Html {
            private boolean enabled = true;
            // Removed filePath as it's now managed by TestHistoryService per run
            // private String filePath = "./reports/html/loadtest-summary-report.html";

            public boolean isEnabled() { return enabled; }
            public void setEnabled(boolean enabled) { this.enabled = enabled; }
            // public String getFilePath() { return filePath; }
            // public void setFilePath(String filePath) { this.filePath = filePath; }
        }

        public static class History {
            private String directory = "./reports/history";
            private boolean saveJson = true;
            private boolean saveCsv = true;

            public String getDirectory() { return directory; }
            public void setDirectory(String directory) { this.directory = directory; }
            public boolean isSaveJson() { return saveJson; }
            public void setSaveJson(boolean saveJson) { this.saveJson = saveJson; }
            public boolean isSaveCsv() { return saveCsv; }
            public void setSaveCsv(boolean saveCsv) { this.saveCsv = saveCsv; }
        }

        // Represents the configuration for where user-defined test scenarios are stored
        public static class Configurations {
            private String directory = "./test_configs";

            public String getDirectory() { return directory; }
            public void setDirectory(String directory) { this.directory = directory; }
        }
    }

    // New inner class for HTTP client specific settings
    public static class Http {
        private Ssl ssl = new Ssl();

        public Ssl getSsl() { return ssl; }
        public void setSsl(Ssl ssl) { this.ssl = ssl; }

        public static class Ssl {
            // Global flag to skip SSL certificate verification for all targets
            // WARNING: Only use this in trusted development/testing environments.
            private boolean insecureSkipVerify = false;

            public boolean isInsecureSkipVerify() {
                return insecureSkipVerify;
            }

            public void setInsecureSkipVerify(boolean insecureSkipVerify) {
                this.insecureSkipVerify = insecureSkipVerify;
            }
        }
    }
}