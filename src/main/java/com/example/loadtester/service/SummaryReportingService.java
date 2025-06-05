package com.example.loadtester.service;

import com.example.loadtester.config.LoadTesterProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import javax.annotation.PreDestroy;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@Service
public class SummaryReportingService {
    private static final Logger logger = LoggerFactory.getLogger(SummaryReportingService.class);
    private final MeterRegistry meterRegistry;
    private final LoadTesterProperties properties;
    private final LoadEmitterService loadEmitterService;
    private final TemplateEngine templateEngine;

    // Holds the latest snapshot of metrics for access by other components (e.g., a dashboard controller)
    // Using AtomicReference for thread-safe updates and reads of the list reference.
    private final AtomicReference<List<TargetReportData>> latestMetricsSnapshot = new AtomicReference<>(Collections.emptyList());

    public SummaryReportingService(MeterRegistry meterRegistry,
                                   LoadTesterProperties properties,
                                   LoadEmitterService loadEmitterService) {
        this.meterRegistry = meterRegistry;
        this.properties = properties;
        this.loadEmitterService = loadEmitterService;

        // Initialize Thymeleaf Template Engine
        ClassLoaderTemplateResolver templateResolver = new ClassLoaderTemplateResolver();
        templateResolver.setSuffix(".html");
        templateResolver.setTemplateMode(TemplateMode.HTML);
        templateResolver.setCharacterEncoding("UTF-8");
        templateResolver.setPrefix("templates/"); // Location of templates in the classpath
        this.templateEngine = new TemplateEngine();
        this.templateEngine.setTemplateResolver(templateResolver);
    }

    /**
     * Data class to hold report information for a single target endpoint.
     * This class should be public if accessed directly by a controller in another package.
     */
    public static class TargetReportData {
        public String name, url, method;
        public int desiredTps;
        public double initiatedRequests, throttledRequests, backpressureDroppedRequests, successfulRequests, failedRequests;
        public LatencyData latencyData;
        public Map<String, Double> statusCodeCounts; // Key: HTTP status code (String), Value: count (Double)

        public TargetReportData() {
            statusCodeCounts = new TreeMap<>(); // Use TreeMap to keep status codes sorted by key
        }
        // Getters might be useful if these objects are serialized to JSON by Spring MVC/WebFlux
        public String getName() { return name; }
        public String getUrl() { return url; }
        public String getMethod() { return method; }
        public int getDesiredTps() { return desiredTps; }
        public double getInitiatedRequests() { return initiatedRequests; }
        public double getThrottledRequests() { return throttledRequests; }
        public double getBackpressureDroppedRequests() { return backpressureDroppedRequests; }
        public double getSuccessfulRequests() { return successfulRequests; }
        public double getFailedRequests() { return failedRequests; }
        public LatencyData getLatencyData() { return latencyData; }
        public Map<String, Double> getStatusCodeCounts() { return statusCodeCounts; }
    }

    /**
     * Data class to hold latency metrics (average, max, count).
     * This class should be public if accessed directly by a controller in another package.
     */
    public static class LatencyData {
        public double avg, max;
        public long count;
        public LatencyData(double avg, double max, long count) {
            this.avg = avg; this.max = max; this.count = count;
        }
        // Getters
        public double getAvg() { return avg; }
        public double getMax() { return max; }
        public long getCount() { return count; }
    }

    /**
     * Retrieves the latest snapshot of collected metrics.
     * This method is intended to be called by a component that serves data to a live dashboard.
     * @return A new list containing the latest {@link TargetReportData} (defensive copy).
     */
    public List<TargetReportData> getLatestMetricsSnapshot() {
        return new ArrayList<>(this.latestMetricsSnapshot.get()); // Return a defensive copy
    }

    /**
     * Generates a summary report, logs it to the console, updates the live metrics snapshot,
     * and generates an HTML report if enabled.
     */
    public void generateAndLogSummaryReport() {
        if (properties.getTargets() == null || properties.getTargets().isEmpty()) {
            logger.info("No targets configured. Skipping summary report generation.");
            this.latestMetricsSnapshot.set(Collections.emptyList()); // Ensure snapshot is empty
            return;
        }

        long reportStartTime = System.currentTimeMillis(); // For measuring report generation time
        logger.info("Generating Load Test Summary Report (and updating live metrics snapshot)...");

        List<TargetReportData> currentMetricsDataList = new ArrayList<>();
        StringBuilder consoleReportBuilder = new StringBuilder("\n\n--- Load Test Summary Report (Console) ---\n");
        // Timestamp for the report
        String currentTimestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        consoleReportBuilder.append(String.format("Report Time: %s\n", currentTimestamp));
        consoleReportBuilder.append("--------------------------------------------------\n");

        // Iterate over each configured target endpoint to gather metrics
        for (LoadTesterProperties.TargetEndpoint targetConfig : properties.getTargets()) {
            TargetReportData reportData = new TargetReportData();
            reportData.name = targetConfig.getName();
            reportData.url = targetConfig.getUrl();
            reportData.method = targetConfig.getMethod();
            reportData.desiredTps = targetConfig.getDesiredTps();

            // Fetch counter values for various request metrics
            reportData.initiatedRequests = getCounterValue("loadtester.requests.initiated", "target", reportData.name);
            reportData.throttledRequests = getCounterValue("loadtester.requests.throttled", "target", reportData.name);
            reportData.backpressureDroppedRequests = getCounterValue("loadtester.requests.backpressure.dropped", "target", reportData.name);

            // Sum counts for successful requests
            List<Tag> successTags = List.of(Tag.of("target", reportData.name), Tag.of("outcome", "success"));
            reportData.successfulRequests = meterRegistry.find("loadtester.requests.completed").tags(successTags).counters()
                    .stream().mapToDouble(Counter::count).sum();

            // Sum counts for failed requests
            List<Tag> failureTags = List.of(Tag.of("target", reportData.name), Tag.of("outcome", "failure"));
            reportData.failedRequests = meterRegistry.find("loadtester.requests.completed").tags(failureTags).counters()
                    .stream().mapToDouble(Counter::count).sum();

            // Collect counts for each HTTP status code
            meterRegistry.find("loadtester.requests.by_status").tag("target", reportData.name).counters().forEach(counter -> {
                String httpStatus = counter.getId().getTag("http_status");
                if (httpStatus != null) {
                    reportData.statusCodeCounts.put(httpStatus, counter.count());
                }
            });

            // Fetch latency timer metrics
            Timer latencyTimer = meterRegistry.find("loadtester.request.latency").tag("target", reportData.name).timer();
            if (latencyTimer != null && latencyTimer.count() > 0) {
                reportData.latencyData = new LatencyData(
                        latencyTimer.mean(TimeUnit.MILLISECONDS), // Average latency in milliseconds
                        latencyTimer.max(TimeUnit.MILLISECONDS),  // Maximum latency in milliseconds
                        latencyTimer.count()                      // Total count of recorded latencies
                );
            }
            currentMetricsDataList.add(reportData);

            // Append data for this target to the console report
            consoleReportBuilder.append(String.format("\nTarget: %s (URL: %s, Method: %s)\n",
                    reportData.name, reportData.url, reportData.method));
            consoleReportBuilder.append(String.format("  Desired TPS: %s\n",
                    reportData.desiredTps == 0 ? "Max Effort" : reportData.desiredTps));
            consoleReportBuilder.append(String.format("  Requests Initiated: %.0f\n", reportData.initiatedRequests));
            consoleReportBuilder.append(String.format("  Requests Throttled (Rate Limiter): %.0f\n", reportData.throttledRequests));
            consoleReportBuilder.append(String.format("  Requests Dropped (Backpressure): %.0f\n", reportData.backpressureDroppedRequests));
            consoleReportBuilder.append(String.format("  Successful Requests (2xx): %.0f\n", reportData.successfulRequests));
            consoleReportBuilder.append(String.format("  Failed Requests (non-2xx/errors): %.0f\n", reportData.failedRequests));
            if (reportData.latencyData != null) {
                consoleReportBuilder.append(String.format("  Latency (ms): Avg=%.2f, Max=%.2f, Count=%d\n",
                        reportData.latencyData.avg, reportData.latencyData.max, reportData.latencyData.count));
            } else {
                consoleReportBuilder.append("  Latency (ms): No latency data recorded.\n");
            }
            if (!reportData.statusCodeCounts.isEmpty()) {
                consoleReportBuilder.append("  Status Code Counts:\n");
                reportData.statusCodeCounts.forEach((status, count) ->
                        consoleReportBuilder.append(String.format("    %s: %.0f\n", status, count))
                );
            }
            consoleReportBuilder.append("--------------------------------------------------\n");
        }
        consoleReportBuilder.append("--- End of Console Report ---\n");
        logger.info(consoleReportBuilder.toString()); // Log the full console report

        // Update the live metrics snapshot with a copy of the currently collected data
        this.latestMetricsSnapshot.set(new ArrayList<>(currentMetricsDataList));

        // Generate HTML report if enabled in properties
        if (properties.getReporting().getHtml().isEnabled()) {
            generateHtmlReport(currentMetricsDataList, currentTimestamp);
        }

        logger.info("Summary Report generation and snapshot update took {} ms.", (System.currentTimeMillis() - reportStartTime));
    }

    /**
     * Generates an HTML report using Thymeleaf.
     * @param metricsDataList List of data for each target.
     * @param reportTimestamp The timestamp when the report was generated.
     */
    private void generateHtmlReport(List<TargetReportData> metricsDataList, String reportTimestamp) {
        Context context = new Context(); // Thymeleaf context
        context.setVariable("reportTimestamp", reportTimestamp);
        context.setVariable("runDurationMinutes", properties.getRunDurationMinutes());
        context.setVariable("targetReports", metricsDataList); // Use the passed-in list

        String originalReportFilePath = properties.getReporting().getHtml().getFilePath();
        String uniqueReportFilePath;

        // Create a filesystem-friendly timestamp (e.g., "20231027_103000" from "2023-10-27 10:30:00")
        String fileFriendlyTimestamp = reportTimestamp.replace(":", "").replace("-", "").replace(" ", "_");

        int dotIndex = originalReportFilePath.lastIndexOf('.');
        if (dotIndex > 0 && dotIndex < originalReportFilePath.length() - 1) { // Check if '.' exists and is not the first or last char
            String baseName = originalReportFilePath.substring(0, dotIndex); // Part before the last dot
            String extension = originalReportFilePath.substring(dotIndex);    // Part from the last dot onwards (e.g., ".html")
            uniqueReportFilePath = baseName + "_" + fileFriendlyTimestamp + extension;
        } else {
            // Fallback if no extension is found or path format is unusual, just append timestamp
            uniqueReportFilePath = originalReportFilePath + "_" + fileFriendlyTimestamp;
            if (!originalReportFilePath.toLowerCase().endsWith(".html") && !originalReportFilePath.toLowerCase().endsWith(".htm")) {
                uniqueReportFilePath += ".html"; // Ensure it has an html extension if none was detected
            }
        }

        try (FileWriter writer = new FileWriter(uniqueReportFilePath)) { // Use the uniquely generated file path
            templateEngine.process("summary-report", context, writer); // Process the template
            logger.info("HTML summary report generated successfully at: {}", Paths.get(uniqueReportFilePath).toAbsolutePath());
        } catch (IOException e) {
            logger.error("Failed to write HTML summary report to file: {}", uniqueReportFilePath, e);
        } catch (Exception e) {
            // Catch any other exceptions during template processing
            logger.error("Error generating HTML report", e);
        }
    }

    /**
     * Helper method to safely get a counter's value.
     * @param name The name of the counter.
     * @param tags Tags to filter the counter.
     * @return The counter's value, or 0.0 if not found.
     */
    private double getCounterValue(String name, String... tags) {
        Counter counter = meterRegistry.find(name).tags(tags).counter();
        return (counter != null) ? counter.count() : 0.0;
    }

    /**
     * This method is called just before the application shuts down.
     * It stops all load emitters and generates a final summary report (which also updates the snapshot).
     */
    @PreDestroy
    public void onShutdown() {
        logger.info("Application shutting down. Stopping all active load emitters...");
        if (loadEmitterService != null) { // Add null check for safety, though it should be injected
            loadEmitterService.stopEmitters(); // Corrected method name
        }

        if (properties.getReporting().getShutdown().isEnabled()) {
            logger.info("Generating final summary report due to application shutdown...");
            generateAndLogSummaryReport(); // This will also update the latestMetricsSnapshot
        } else {
            logger.info("Shutdown summary report is disabled via configuration. Skipping final report.");
        }
    }
}
