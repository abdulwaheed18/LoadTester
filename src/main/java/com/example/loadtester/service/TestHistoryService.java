package com.example.loadtester.service;

import com.example.loadtester.config.LoadTesterProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class TestHistoryService {
    private static final Logger logger = LoggerFactory.getLogger(TestHistoryService.class);
    private final String historyDirectory;
    private final ObjectMapper objectMapper; // For reading JSON reports

    public static class TestRunSummary {
        private String runId;
        private String reportTimestamp; // From the JSON file or directory creation time
        private String htmlReportPath; // Relative path or link
        private String jsonReportPath;
        private String csvReportPath;
        private long totalRequests; // Example summary metric
        private double successRate; // Example summary metric

        // Constructor, Getters, Setters
        public TestRunSummary(String runId, String reportTimestamp) {
            this.runId = runId;
            this.reportTimestamp = reportTimestamp;
        }

        public String getRunId() { return runId; }
        public void setRunId(String runId) { this.runId = runId; }
        public String getReportTimestamp() { return reportTimestamp; }
        public void setReportTimestamp(String reportTimestamp) { this.reportTimestamp = reportTimestamp; }
        public String getHtmlReportPath() { return htmlReportPath; }
        public void setHtmlReportPath(String htmlReportPath) { this.htmlReportPath = htmlReportPath; }
        public String getJsonReportPath() { return jsonReportPath; }
        public void setJsonReportPath(String jsonReportPath) { this.jsonReportPath = jsonReportPath; }
        public String getCsvReportPath() { return csvReportPath; }
        public void setCsvReportPath(String csvReportPath) { this.csvReportPath = csvReportPath; }
        public long getTotalRequests() { return totalRequests; }
        public void setTotalRequests(long totalRequests) { this.totalRequests = totalRequests; }
        public double getSuccessRate() { return successRate; }
        public void setSuccessRate(double successRate) { this.successRate = successRate; }
    }

    @Autowired
    public TestHistoryService(LoadTesterProperties properties, ObjectMapper objectMapper) {
        this.historyDirectory = properties.getReporting().getHistory().getDirectory();
        this.objectMapper = objectMapper;
        // Ensure history directory exists on startup
        try {
            Files.createDirectories(Paths.get(this.historyDirectory));
        } catch (IOException e) {
            logger.error("Failed to create history directory on startup: {}", this.historyDirectory, e);
        }
    }

    public List<TestRunSummary> listTestRuns() {
        Path historyPath = Paths.get(historyDirectory);
        if (!Files.exists(historyPath) || !Files.isDirectory(historyPath)) {
            logger.warn("History directory does not exist or is not a directory: {}", historyDirectory);
            return Collections.emptyList();
        }

        List<TestRunSummary> summaries = new ArrayList<>();
        try (Stream<Path> runDirs = Files.list(historyPath)) {
            runDirs.filter(Files::isDirectory).forEach(runDir -> {
                String runId = runDir.getFileName().toString();
                Path jsonReportFile = runDir.resolve("metrics-report.json");
                String timestampStr = null;
                long totalRequests = 0;
                double successfulRequests = 0;

                if (Files.exists(jsonReportFile)) {
                    try {
                        Map<String, Object> reportWrapper = objectMapper.readValue(jsonReportFile.toFile(), new TypeReference<Map<String, Object>>() {});
                        timestampStr = (String) reportWrapper.get("reportTimestamp");
                        List<Map<String, Object>> metrics = (List<Map<String, Object>>) reportWrapper.get("metrics");
                        if (metrics != null) {
                            for (Map<String, Object> targetMetricMap : metrics) {
                                totalRequests += ((Number) targetMetricMap.getOrDefault("initiatedRequests", 0)).longValue();
                                successfulRequests += ((Number) targetMetricMap.getOrDefault("successfulRequests", 0)).doubleValue();
                            }
                        }
                    } catch (IOException e) {
                        logger.error("Failed to read or parse JSON report for run ID {}: {}", runId, jsonReportFile, e);
                    }
                }

                if (timestampStr == null) { // Fallback to directory creation time
                    try {
                        BasicFileAttributes attrs = Files.readAttributes(runDir, BasicFileAttributes.class);
                        timestampStr = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(attrs.creationTime().toMillis()));
                    } catch (IOException e) {
                        logger.error("Failed to get creation time for directory: {}", runDir, e);
                        timestampStr = "Unknown";
                    }
                }

                TestRunSummary summary = new TestRunSummary(runId, timestampStr);
                if (Files.exists(runDir.resolve("summary-report.html"))) {
                    summary.setHtmlReportPath(runId + "/summary-report.html"); // Relative path for linking
                }
                if (Files.exists(jsonReportFile)) {
                    summary.setJsonReportPath(runId + "/metrics-report.json");
                }
                if (Files.exists(runDir.resolve("metrics-report.csv"))) {
                    summary.setCsvReportPath(runId + "/metrics-report.csv");
                }
                summary.setTotalRequests(totalRequests);
                if (totalRequests > 0) {
                    summary.setSuccessRate((successfulRequests / totalRequests) * 100.0);
                } else {
                    summary.setSuccessRate(0.0);
                }

                summaries.add(summary);
            });
        } catch (IOException e) {
            logger.error("Failed to list test run directories in {}", historyDirectory, e);
            return Collections.emptyList();
        }

        // Sort by timestamp, most recent first
        summaries.sort((s1, s2) -> {
            try {
                Date d1 = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(s1.getReportTimestamp());
                Date d2 = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(s2.getReportTimestamp());
                return d2.compareTo(d1);
            } catch (ParseException e) {
                return 0; // Should not happen if timestamps are well-formed
            }
        });
        return summaries;
    }

    public List<SummaryReportingService.TargetReportData> getTestRunDetails(String runId) throws IOException {
        Path jsonReportFile = Paths.get(historyDirectory, runId, "metrics-report.json");
        if (!Files.exists(jsonReportFile)) {
            logger.warn("JSON report not found for run ID: {}", runId);
            return null;
        }
        Map<String, Object> reportWrapper = objectMapper.readValue(jsonReportFile.toFile(), new TypeReference<Map<String, Object>>() {});
        // Need to properly convert List<Map<String, Object>> back to List<TargetReportData>
        // This requires TargetReportData to be deserializable by Jackson, or manual mapping.
        // For now, returning raw maps which might not be ideal for strong typing.
        // A better way: objectMapper.convertValue(reportWrapper.get("metrics"), new TypeReference<List<SummaryReportingService.TargetReportData>>() {});
        // This requires TargetReportData to have a default constructor and setters, or be Jackson-annotated.
        List<Map<String, Object>> metricsMaps = (List<Map<String, Object>>) reportWrapper.get("metrics");
        return metricsMaps.stream()
                .map(map -> objectMapper.convertValue(map, SummaryReportingService.TargetReportData.class))
                .collect(Collectors.toList());
    }

    public File getTestRunJsonFile(String runId) {
        Path filePath = Paths.get(historyDirectory, runId, "metrics-report.json");
        return Files.exists(filePath) ? filePath.toFile() : null;
    }

    public File getTestRunCsvFile(String runId) {
        Path filePath = Paths.get(historyDirectory, runId, "metrics-report.csv");
        return Files.exists(filePath) ? filePath.toFile() : null;
    }
    public File getTestRunHtmlFile(String runId) {
        Path filePath = Paths.get(historyDirectory, runId, "summary-report.html");
        return Files.exists(filePath) ? filePath.toFile() : null;
    }

}
