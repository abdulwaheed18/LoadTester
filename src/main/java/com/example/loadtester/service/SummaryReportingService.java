package com.example.loadtester.service;

import com.example.loadtester.config.LoadTesterProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import javax.annotation.PreDestroy;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
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
    private final LoadEmitterService loadEmitterService; // Still needed for @PreDestroy to stop emitters
    private final TemplateEngine templateEngine;
    private final ObjectMapper objectMapper; // For JSON serialization

    private final AtomicReference<List<TargetReportData>> latestMetricsSnapshot = new AtomicReference<>(Collections.emptyList());

    @Autowired
    public SummaryReportingService(MeterRegistry meterRegistry,
                                   LoadTesterProperties properties,
                                   @org.springframework.context.annotation.Lazy LoadEmitterService loadEmitterService, // Lazy to break cycle
                                   TemplateEngine templateEngine) { // Assuming TemplateEngine is autoconfigured by Spring Boot
        this.meterRegistry = meterRegistry;
        this.properties = properties;
        this.loadEmitterService = loadEmitterService;
        this.templateEngine = templateEngine;

        this.objectMapper = new ObjectMapper();
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT); // Pretty print JSON
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        // Using a specific date format for JSON, if needed for 'reportTimestamp' field serialization.
        this.objectMapper.setDateFormat(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss"));
    }

    // Getter for properties, potentially used by other components if needed (e.g. TestHistoryService if it didn't take LoadTesterProperties directly)
    public LoadTesterProperties getProperties() {
        return properties;
    }


    public static class TargetReportData {
        // Fields remain public for direct access or Jackson serialization/deserialization
        public String name, url, method;
        public int desiredTps;
        public double initiatedRequests, throttledRequests, backpressureDroppedRequests, successfulRequests, failedRequests;
        public LatencyData latencyData;
        public Map<String, Double> statusCodeCounts;

        public TargetReportData() { // Default constructor needed for Jackson deserialization
            statusCodeCounts = new TreeMap<>(); // Initialize to avoid NullPointerExceptions
        }
        // Getters are good practice and can be used by Jackson or Thymeleaf
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

    public static class LatencyData {
        public double avg, max;
        public long count;

        public LatencyData() {} // Default constructor for Jackson

        public LatencyData(double avg, double max, long count) {
            this.avg = avg; this.max = max; this.count = count;
        }
        public double getAvg() { return avg; }
        public double getMax() { return max; }
        public long getCount() { return count; }
    }

    public List<TargetReportData> getLatestMetricsSnapshot() {
        // Return a defensive copy to prevent modification of the internal list
        return new ArrayList<>(this.latestMetricsSnapshot.get());
    }

    /**
     * Generates reports based on the current metrics.
     * This is the primary method for generating all report types (console, HTML, JSON, CSV).
     * @param runId The specific run ID for this report generation. If null or empty, a generic timestamp-based ID is used.
     */
    public void generateAndLogSummaryReport(String runId) {
        if (properties.getTargets() == null || properties.getTargets().isEmpty()) {
            logger.info("No targets configured. Skipping summary report generation.");
            this.latestMetricsSnapshot.set(Collections.emptyList()); // Clear snapshot if no targets
            return;
        }

        long reportGenStartTime = System.currentTimeMillis(); // For timing report generation
        // Determine an effective runId for reporting if the provided one is blank
        String effectiveRunId = (runId != null && !runId.trim().isEmpty())
                ? runId
                : "adhoc_report_" + new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        logger.info("Generating Summary Report for Run ID: {}...", effectiveRunId);


        List<TargetReportData> currentMetricsDataList = new ArrayList<>();
        StringBuilder consoleReportBuilder = new StringBuilder("\n\n--- Load Test Summary Report (Console) ---\n");
        String currentReportTimestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")); // Timestamp for this report
        consoleReportBuilder.append(String.format("Report Time: %s\n", currentReportTimestamp));
        consoleReportBuilder.append(String.format("Run ID: %s\n", effectiveRunId));
        consoleReportBuilder.append("--------------------------------------------------\n");

        // Iterate over each configured target endpoint to gather and report metrics
        for (LoadTesterProperties.TargetEndpoint targetConfig : properties.getTargets()) {
            TargetReportData reportData = new TargetReportData();
            reportData.name = targetConfig.getName();
            reportData.url = targetConfig.getUrl();
            reportData.method = targetConfig.getMethod();
            reportData.desiredTps = targetConfig.getDesiredTps();

            // Fetch various request counters from Micrometer MeterRegistry
            reportData.initiatedRequests = getCounterValue("loadtester.requests.initiated", "target", reportData.name);
            reportData.throttledRequests = getCounterValue("loadtester.requests.throttled", "target", reportData.name);
            reportData.backpressureDroppedRequests = getCounterValue("loadtester.requests.backpressure.dropped", "target", reportData.name);

            List<Tag> successTags = List.of(Tag.of("target", reportData.name), Tag.of("outcome", "success"));
            reportData.successfulRequests = meterRegistry.find("loadtester.requests.completed").tags(successTags).counters()
                    .stream().mapToDouble(Counter::count).sum();

            List<Tag> failureTags = List.of(Tag.of("target", reportData.name), Tag.of("outcome", "failure"));
            reportData.failedRequests = meterRegistry.find("loadtester.requests.completed").tags(failureTags).counters()
                    .stream().mapToDouble(Counter::count).sum();

            // Collect HTTP status code counts
            meterRegistry.find("loadtester.requests.by_status").tag("target", reportData.name).counters().forEach(counter -> {
                String httpStatus = counter.getId().getTag("http_status");
                if (httpStatus != null) {
                    reportData.statusCodeCounts.put(httpStatus, counter.count());
                }
            });

            // Collect latency metrics
            Timer latencyTimer = meterRegistry.find("loadtester.request.latency").tag("target", reportData.name).timer();
            if (latencyTimer != null && latencyTimer.count() > 0) {
                reportData.latencyData = new LatencyData(
                        latencyTimer.mean(TimeUnit.MILLISECONDS),
                        latencyTimer.max(TimeUnit.MILLISECONDS),
                        latencyTimer.count()
                );
            }
            currentMetricsDataList.add(reportData);

            // Append data for this target to the console report string builder
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
        logger.info(consoleReportBuilder.toString()); // Log the complete console report

        // Update the live metrics snapshot for UI display
        this.latestMetricsSnapshot.set(new ArrayList<>(currentMetricsDataList));

        // Ensure history base directory and run-specific directory exist
        Path historyBaseDir = Paths.get(properties.getReporting().getHistory().getDirectory());
        Path runSpecificDir = historyBaseDir.resolve(effectiveRunId);
        try {
            Files.createDirectories(runSpecificDir); // Creates base and run-specific dirs if they don't exist
        } catch (IOException e) {
            logger.error("Failed to create history directory path: {}. Report files may not be saved.", runSpecificDir, e);
            // Depending on policy, could return here or just log and continue without file saving.
        }

        // Generate file-based reports (HTML, JSON, CSV) if enabled in properties
        if (properties.getReporting().getHtml().isEnabled()) {
            generateHtmlReport(currentMetricsDataList, effectiveRunId, currentReportTimestamp, runSpecificDir);
        }
        if (properties.getReporting().getHistory().isSaveJson()) {
            generateJsonReport(currentMetricsDataList, effectiveRunId, currentReportTimestamp, runSpecificDir);
        }
        if (properties.getReporting().getHistory().isSaveCsv()) {
            generateCsvReport(currentMetricsDataList, effectiveRunId, currentReportTimestamp, runSpecificDir);
        }

        logger.info("Summary Report generation and snapshot update for Run ID: {} completed in {} ms.", effectiveRunId, (System.currentTimeMillis() - reportGenStartTime));
    }

    private void generateHtmlReport(List<TargetReportData> metricsDataList, String runId, String reportTimestamp, Path runSpecificDir) {
        Context context = new Context();
        context.setVariable("reportTimestamp", reportTimestamp);
        context.setVariable("runId", runId);
        context.setVariable("runDurationSeconds", properties.getRunDurationSeconds());
        context.setVariable("targetReports", metricsDataList);

        // Standardized HTML report filename within the run-specific directory
        Path reportFilePath = runSpecificDir.resolve("summary-report.html");

        try (FileWriter writer = new FileWriter(reportFilePath.toFile())) {
            if (templateEngine == null) { // Should be injected by Spring
                logger.error("TemplateEngine is null. Cannot generate HTML report for run ID: {}. Check Thymeleaf configuration.", runId);
                return;
            }
            templateEngine.process("summary-report", context, writer); // "summary-report" is the template name (summary-report.html)
            logger.info("HTML summary report for run ID: {} generated at: {}", runId, reportFilePath.toAbsolutePath());
        } catch (IOException e) {
            logger.error("Failed to write HTML summary report for run ID: {} to file: {}", runId, reportFilePath, e);
        } catch (Exception e) { // Catch broader Thymeleaf or other processing errors
            logger.error("Error generating HTML report for run ID: {}. Details: {}", runId, e.getMessage(), e);
        }
    }

    private void generateJsonReport(List<TargetReportData> metricsDataList, String runId, String reportTimestamp, Path runSpecificDir) {
        Path reportFilePath = runSpecificDir.resolve("metrics-report.json");
        try (FileWriter writer = new FileWriter(reportFilePath.toFile())) {
            Map<String, Object> reportWrapper = new TreeMap<>(); // Use TreeMap for consistent key order in JSON
            reportWrapper.put("runId", runId);
            reportWrapper.put("reportTimestamp", reportTimestamp);
            reportWrapper.put("metrics", metricsDataList); // The actual list of metrics data
            objectMapper.writeValue(writer, reportWrapper);
            logger.info("JSON metrics report for run ID: {} generated at: {}", runId, reportFilePath.toAbsolutePath());
        } catch (IOException e) {
            logger.error("Failed to write JSON metrics report for run ID: {} to file: {}", runId, reportFilePath, e);
        }
    }

    private void generateCsvReport(List<TargetReportData> metricsDataList, String runId, String reportTimestamp, Path runSpecificDir) {
        Path reportFilePath = runSpecificDir.resolve("metrics-report.csv");
        // Define CSV headers
        String[] headers = {
                "RunID", "ReportTimestamp", "TargetName", "URL", "Method", "DesiredTPS",
                "InitiatedRequests", "ThrottledRequests", "BackpressureDropped",
                "SuccessfulRequests", "FailedRequests",
                "AvgLatencyMs", "MaxLatencyMs", "LatencyCount",
                "StatusCodesJson" // Storing complex map data (like status codes) as a JSON string within a CSV cell
        };

        try (FileWriter out = new FileWriter(reportFilePath.toFile());
             CSVPrinter printer = new CSVPrinter(out, CSVFormat.DEFAULT.withHeader(headers))) {

            for (TargetReportData data : metricsDataList) {
                printer.printRecord(
                        runId,
                        reportTimestamp,
                        data.getName(),
                        data.getUrl(),
                        data.getMethod(),
                        data.getDesiredTps() == 0 ? "Max Effort" : String.valueOf(data.getDesiredTps()),
                        data.getInitiatedRequests(),
                        data.getThrottledRequests(),
                        data.getBackpressureDroppedRequests(),
                        data.getSuccessfulRequests(),
                        data.getFailedRequests(),
                        data.getLatencyData() != null ? String.format("%.2f", data.getLatencyData().getAvg()) : "N/A",
                        data.getLatencyData() != null ? String.format("%.2f", data.getLatencyData().getMax()) : "N/A",
                        data.getLatencyData() != null ? data.getLatencyData().getCount() : "N/A",
                        objectMapper.writeValueAsString(data.getStatusCodeCounts()) // Serialize status codes map to JSON
                );
            }
            logger.info("CSV metrics report for run ID: {} generated at: {}", runId, reportFilePath.toAbsolutePath());
        } catch (IOException e) {
            logger.error("Failed to write CSV metrics report for run ID: {} to file: {}", runId, reportFilePath, e);
        }
    }

    private double getCounterValue(String name, String... tags) {
        Counter counter = meterRegistry.find(name).tags(tags).counter();
        return (counter != null) ? counter.count() : 0.0;
    }

    /**
     * This method is called by Spring just before the application context is destroyed.
     * It ensures that load emitters are stopped and a final report is generated if configured.
     */
    @PreDestroy
    public void onShutdown() {
        logger.info("Application context is shutting down. Performing cleanup in SummaryReportingService.");
        String finalRunId = null;

        // loadEmitterService might be null if @Lazy initialization failed, though unlikely in normal operation
        if (loadEmitterService != null && loadEmitterService.areEmittersRunning()) {
            finalRunId = loadEmitterService.getCurrentRunId(); // Get runId if a test is active
            logger.info("Load emitters are currently running with ID: {} during application shutdown. Stopping them now.", finalRunId);
            loadEmitterService.stopEmitters(); // Ensure all load generation is stopped
        }

        // Generate a final report if shutdown reporting is enabled
        if (properties.getReporting().getShutdown().isEnabled()) {
            String reportIdForShutdown = (finalRunId != null)
                    ? finalRunId // Use active runId if available
                    : "shutdown_report_" + new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date()); // Generate a unique ID for this shutdown report

            logger.info("Generating final summary report due to application shutdown. Report ID: {}", reportIdForShutdown);
            generateAndLogSummaryReport(reportIdForShutdown);
        } else {
            logger.info("Shutdown summary report is disabled. Skipping final report generation during application shutdown.");
        }
    }
}