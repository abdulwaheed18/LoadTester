// src/main/java/com/example/loadtester/controller/LoadTestController.java
package com.example.loadtester.controller;

import com.example.loadtester.config.LoadTesterProperties;
import com.example.loadtester.model.TestConfiguration;
import com.example.loadtester.service.LoadEmitterService;
import com.example.loadtester.service.SummaryReportingService;
import com.example.loadtester.service.TestConfigurationService;
import com.example.loadtester.service.TestHistoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Controller
public class LoadTestController {

    private static final Logger logger = LoggerFactory.getLogger(LoadTestController.class);

    private final LoadEmitterService loadEmitterService;
    private final SummaryReportingService summaryReportingService;
    private final TestHistoryService testHistoryService;
    private final LoadTesterProperties defaultLoadTesterProperties;
    private final TestConfigurationService testConfigurationService;

    @Autowired
    public LoadTestController(LoadEmitterService loadEmitterService,
                              SummaryReportingService summaryReportingService,
                              TestHistoryService testHistoryService,
                              LoadTesterProperties loadTesterProperties,
                              TestConfigurationService testConfigurationService) {
        this.loadEmitterService = loadEmitterService;
        this.summaryReportingService = summaryReportingService;
        this.testHistoryService = testHistoryService;
        this.defaultLoadTesterProperties = loadTesterProperties;
        this.testConfigurationService = testConfigurationService;
    }

    @GetMapping("/")
    public String dashboard(Model model) {
        model.addAttribute("loadTestActive", loadEmitterService.areEmittersRunning());
        model.addAttribute("currentRunId", loadEmitterService.getCurrentRunId());
        logger.info("Serving dashboard page.");
        return "dashboard";
    }

    @GetMapping("/history")
    public String historyPage(Model model) {
        logger.info("Serving test history page.");
        return "history";
    }

    @GetMapping(value = "/api/loadtest/config", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getLoadTestConfig(@RequestParam(required = false) String configurationId) {
        try {
            LoadTesterProperties effectiveProperties;
            String configName = "Default (application.yml)";

            if (configurationId != null && !configurationId.isEmpty()) {
                Optional<TestConfiguration> userConfigOpt = testConfigurationService.getConfigurationById(configurationId);
                if (userConfigOpt.isPresent()) {
                    TestConfiguration userConfig = userConfigOpt.get();
                    effectiveProperties = testConfigurationService.convertToLoadTesterProperties(userConfig, defaultLoadTesterProperties);
                    configName = userConfig.getName();
                } else {
                    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Collections.singletonMap("error", "Configuration ID not found: " + configurationId));
                }
            } else {
                effectiveProperties = defaultLoadTesterProperties;
            }

            Map<String, Object> configMap = new HashMap<>();
            configMap.put("activeConfigurationName", configName);

            if (effectiveProperties != null) {
                if (effectiveProperties.getTargets() != null) {
                    configMap.put("targets", effectiveProperties.getTargets().stream()
                            .map(target -> {
                                Map<String, Object> targetInfo = new HashMap<>();
                                targetInfo.put("name", target.getName());
                                targetInfo.put("url", target.getUrl());
                                targetInfo.put("method", target.getMethod());
                                targetInfo.put("desiredTps", target.getDesiredTps());
                                targetInfo.put("throttleIntervalMs", target.getThrottleIntervalMs());
                                targetInfo.put("hasPayload", target.getPayloadPath() != null && !target.getPayloadPath().isEmpty());
                                return targetInfo;
                            }).collect(Collectors.toList()));
                }
                configMap.put("runDurationMinutesConfigured", effectiveProperties.getRunDurationMinutes());
                if (effectiveProperties.getReporting() != null && effectiveProperties.getReporting().getHistory() != null) {
                    configMap.put("historyDirectory", effectiveProperties.getReporting().getHistory().getDirectory());
                }
                if (effectiveProperties.getReporting() != null && effectiveProperties.getReporting().getPeriodic() != null) {
                    configMap.put("periodicReportingEnabled", effectiveProperties.getReporting().getPeriodic().isEnabled());
                    configMap.put("periodicReportIntervalMs", effectiveProperties.getReporting().getPeriodic().getSummaryIntervalMs());
                }
            } else {
                configMap.put("error", "LoadTesterProperties (effective) not available.");
            }
            return ResponseEntity.ok(configMap);
        } catch (Exception e) {
            logger.error("Error retrieving load test configuration", e);
            return ResponseEntity.status(500).body(Collections.singletonMap("error", "Could not retrieve configuration: " + e.getMessage()));
        }
    }

    @PostMapping(value = "/api/loadtest/configurations", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<TestConfiguration> saveConfiguration(@RequestBody TestConfiguration config) {
        try {
            if (config.getId() == null || config.getId().isEmpty()) {
                config.setId(UUID.randomUUID().toString());
            }
            TestConfiguration savedConfig = testConfigurationService.saveConfiguration(config);
            return ResponseEntity.ok(savedConfig);
        } catch (IOException e) {
            logger.error("Error saving test configuration: " + (config != null ? config.getName() : "null"), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(null);
        }
    }

    @GetMapping(value = "/api/loadtest/configurations", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<List<TestConfiguration>> getAllConfigurations() {
        try {
            return ResponseEntity.ok(testConfigurationService.getAllConfigurations());
        } catch (Exception e) {
            logger.error("Error fetching all test configurations", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Collections.emptyList());
        }
    }

    @GetMapping(value = "/api/loadtest/configurations/{configId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<TestConfiguration> getConfigurationById(@PathVariable String configId) {
        return testConfigurationService.getConfigurationById(configId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping(value = "/api/loadtest/configurations/{configId}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<TestConfiguration> updateConfiguration(@PathVariable String configId, @RequestBody TestConfiguration config) {
        if (!testConfigurationService.getConfigurationById(configId).isPresent()) {
            logger.warn("Attempted to update non-existent configuration ID: {}", configId);
            return ResponseEntity.notFound().build();
        }
        config.setId(configId);
        try {
            TestConfiguration updatedConfig = testConfigurationService.saveConfiguration(config);
            return ResponseEntity.ok(updatedConfig);
        } catch (IOException e) {
            logger.error("Error updating test configuration: " + config.getName(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(null);
        }
    }

    @DeleteMapping(value = "/api/loadtest/configurations/{configId}")
    @ResponseBody
    public ResponseEntity<Void> deleteConfiguration(@PathVariable String configId) {
        if (!testConfigurationService.getConfigurationById(configId).isPresent()) {
            logger.warn("Attempted to delete non-existent configuration ID: {}", configId);
            return ResponseEntity.notFound().build();
        }
        if (testConfigurationService.deleteConfiguration(configId)) {
            return ResponseEntity.ok().build();
        } else {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @PostMapping("/api/loadtest/start")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> startLoadTest(@RequestParam(required = false) String configurationId) {
        logger.info("Received request to start load test. Custom config ID: {}",
                (configurationId == null || configurationId.isEmpty()) ? "None (using default)" : configurationId);
        Map<String, Object> response = new HashMap<>();
        try {
            if (loadEmitterService.areEmittersRunning()) {
                response.put("message", "Load test is already running with run ID: " + loadEmitterService.getCurrentRunId());
                response.put("runId", loadEmitterService.getCurrentRunId());
                logger.warn("Attempted to start load test while it was already running.");
                return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
            }

            LoadTesterProperties propertiesToUse;
            String loadedConfigName;

            if (configurationId != null && !configurationId.isEmpty()) {
                Optional<TestConfiguration> userConfigOpt = testConfigurationService.getConfigurationById(configurationId);
                if (userConfigOpt.isPresent()) {
                    TestConfiguration userConfig = userConfigOpt.get();
                    propertiesToUse = testConfigurationService.convertToLoadTesterProperties(userConfig, defaultLoadTesterProperties);
                    loadedConfigName = userConfig.getName();
                    logger.info("Starting test with user-defined configuration: {} (ID: {})", loadedConfigName, configurationId);
                } else {
                    response.put("message", "Configuration ID '" + configurationId + "' not found. Cannot start test.");
                    logger.warn("Configuration ID {} not found.", configurationId);
                    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
                }
            } else {
                propertiesToUse = defaultLoadTesterProperties;
                loadedConfigName = "Default (application.yml)";
                logger.info("Starting test with default configuration from application.yml.");
            }

            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
            String uniquePart = UUID.randomUUID().toString().substring(0, 8);
            String runId = "run_" + timestamp + "_" + uniquePart;

            // *** FIX: Pass the loadedConfigName as the third argument ***
            loadEmitterService.startEmitters(runId, propertiesToUse, loadedConfigName);

            response.put("message", "Load test started successfully with run ID: " + runId + " using configuration: " + loadedConfigName);
            response.put("runId", runId);
            response.put("configName", loadedConfigName);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Error starting load test", e);
            response.put("message", "Error starting load test: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    @PostMapping("/api/loadtest/stop")
    @ResponseBody
    public ResponseEntity<Map<String, String>> stopLoadTest() {
        logger.info("Received request to stop load test.");
        Map<String, String> response = new HashMap<>();
        String currentRunId = loadEmitterService.getCurrentRunId();
        try {
            if (!loadEmitterService.areEmittersRunning()) {
                response.put("message", "Load test is not currently running.");
                logger.warn("Attempted to stop load test while it was not running.");
                return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
            }
            loadEmitterService.stopEmitters();

            LoadTesterProperties currentRunProps = loadEmitterService.getActiveRunProperties();
            // Determine if shutdown report is enabled based on the properties active during the run,
            // or fall back to default if for some reason activeRunProps is null.
            boolean shutdownReportEnabled = defaultLoadTesterProperties.getReporting().getShutdown().isEnabled();
            if (currentRunProps != null && currentRunProps.getReporting() != null && currentRunProps.getReporting().getShutdown() != null) {
                shutdownReportEnabled = currentRunProps.getReporting().getShutdown().isEnabled();
            } else if (currentRunProps == null) {
                logger.warn("activeRunProperties was null during stopLoadTest for runId: {}. Using default reporting settings for shutdown report decision.", currentRunId);
            }


            if (currentRunId != null && shutdownReportEnabled) {
                logger.info("Generating final report for manually stopped run ID: {}", currentRunId);
                summaryReportingService.generateAndLogSummaryReport(currentRunId);
            }
            loadEmitterService.finalizeRunSessionState(); // Use the corrected method name

            response.put("message", "Load test (run ID: " + currentRunId + ") stopped successfully.");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Error stopping load test for run ID: " + currentRunId, e);
            response.put("message", "Error stopping load test: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    @GetMapping(value = "/api/loadtest/status", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<List<SummaryReportingService.TargetReportData>> getLoadTestStatus() {
        try {
            List<SummaryReportingService.TargetReportData> metrics = summaryReportingService.getLatestMetricsSnapshot();
            return ResponseEntity.ok(metrics);
        } catch (Exception e) {
            logger.error("Error retrieving load test status", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Collections.emptyList());
        }
    }

    @GetMapping(value = "/api/loadtest/isrunning", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<Map<String, Object>> isLoadTestRunning() {
        Map<String, Object> response = new HashMap<>();
        boolean running = loadEmitterService.areEmittersRunning();
        response.put("running", running);
        response.put("currentRunId", running ? loadEmitterService.getCurrentRunId() : null);
        response.put("currentConfigName", running ? loadEmitterService.getCurrentConfigName() : "N/A");
        return ResponseEntity.ok(response);
    }

    @GetMapping(value = "/api/loadtest/history", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<List<TestHistoryService.TestRunSummary>> getTestHistory() {
        try {
            return ResponseEntity.ok(testHistoryService.listTestRuns());
        } catch (Exception e) {
            logger.error("Error fetching test history list", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Collections.emptyList());
        }
    }

    @GetMapping(value = "/api/loadtest/history/{runId}/details", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<List<SummaryReportingService.TargetReportData>> getTestRunDetails(@PathVariable String runId) {
        try {
            List<SummaryReportingService.TargetReportData> details = testHistoryService.getTestRunDetails(runId);
            if (details == null || details.isEmpty()) {
                logger.warn("No details found or error occurred for run ID: {}", runId);
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(details);
        } catch (IOException e) {
            logger.error("IOException fetching details for run ID: {}", runId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Collections.emptyList());
        }
        catch (Exception e) {
            logger.error("Generic error fetching details for run ID: {}", runId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Collections.emptyList());
        }
    }

    @GetMapping("/api/loadtest/export/{runId}/{format}")
    public ResponseEntity<InputStreamResource> exportTestRun(
            @PathVariable String runId,
            @PathVariable String format) {
        try {
            File fileToExport = null;
            String contentType = MediaType.APPLICATION_OCTET_STREAM_VALUE;
            String filename = runId.replaceAll("[^a-zA-Z0-9_.-]", "_") + "." + format;

            if ("json".equalsIgnoreCase(format)) {
                fileToExport = testHistoryService.getTestRunJsonFile(runId);
                contentType = MediaType.APPLICATION_JSON_VALUE;
            } else if ("csv".equalsIgnoreCase(format)) {
                fileToExport = testHistoryService.getTestRunCsvFile(runId);
                contentType = "text/csv";
            } else if ("html".equalsIgnoreCase(format)) {
                fileToExport = testHistoryService.getTestRunHtmlFile(runId);
                contentType = MediaType.TEXT_HTML_VALUE;
            } else {
                logger.warn("Unsupported export format requested: {}", format);
                return ResponseEntity.badRequest().build();
            }

            if (fileToExport == null || !fileToExport.exists()) {
                logger.error("File not found for export: run ID {}, format {}", runId, format);
                return ResponseEntity.notFound().build();
            }

            InputStreamResource resource = new InputStreamResource(new FileInputStream(fileToExport));
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment;filename=\"" + filename + "\"")
                    .contentType(MediaType.parseMediaType(contentType))
                    .contentLength(fileToExport.length())
                    .body(resource);

        } catch (FileNotFoundException e) {
            logger.error("File not found for export: run ID {}, format {}", runId, format, e);
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            logger.error("Error exporting test run ID {}: format {}", runId, format, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GetMapping("/api/loadtest/compare")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> compareTestRuns(
            @RequestParam List<String> runIds,
            @RequestParam(required = false) String baselineRunId) {
        try {
            Map<String, Object> comparisonData = new HashMap<>();
            List<Map<String, Object>> runDetailsList = new ArrayList<>();

            for (String runId : runIds) {
                List<SummaryReportingService.TargetReportData> details = testHistoryService.getTestRunDetails(runId);
                Map<String, Object> runEntry = new HashMap<>();
                runEntry.put("id", runId);
                runEntry.put("runId", runId);
                runEntry.put("metrics", details != null ? details : Collections.emptyList());

                Optional<TestHistoryService.TestRunSummary> summary = testHistoryService.listTestRuns().stream().filter(s -> s.getRunId().equals(runId)).findFirst();
                summary.ifPresent(testRunSummary -> runEntry.put("reportTimestamp", testRunSummary.getReportTimestamp()));

                if (runId.equals(baselineRunId)) {
                    runEntry.put("isBaseline", true);
                }
                runDetailsList.add(runEntry);
            }
            comparisonData.put("comparedRuns", runDetailsList);
            comparisonData.put("baselineRunId", baselineRunId);

            return ResponseEntity.ok(comparisonData);
        } catch (Exception e) {
            logger.error("Error preparing comparison data for run IDs: " + runIds, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Collections.singletonMap("error", "Could not generate comparison: " + e.getMessage()));
        }
    }
}
