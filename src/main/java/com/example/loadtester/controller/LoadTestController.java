package com.example.loadtester.controller;

import com.example.loadtester.service.LoadEmitterService;
import com.example.loadtester.service.SummaryReportingService;
import com.example.loadtester.service.TestHistoryService; // New service for history
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Controller
public class LoadTestController {

    private static final Logger logger = LoggerFactory.getLogger(LoadTestController.class);

    private final LoadEmitterService loadEmitterService;
    private final SummaryReportingService summaryReportingService;
    private final TestHistoryService testHistoryService; // Inject TestHistoryService

    @Autowired
    public LoadTestController(LoadEmitterService loadEmitterService,
                              SummaryReportingService summaryReportingService,
                              TestHistoryService testHistoryService) {
        this.loadEmitterService = loadEmitterService;
        this.summaryReportingService = summaryReportingService;
        this.testHistoryService = testHistoryService;
    }

    @GetMapping("/")
    public String dashboard(Model model) {
        model.addAttribute("loadTestActive", loadEmitterService.areEmittersRunning());
        model.addAttribute("currentRunId", loadEmitterService.getCurrentRunId());
        logger.info("Serving dashboard page.");
        return "dashboard";
    }

    /**
     * Serves the test history page.
     * @param model Model for Thymeleaf.
     * @return The name of the history HTML template.
     */
    @GetMapping("/history")
    public String historyPage(Model model) {
        logger.info("Serving test history page.");
        // Model attributes can be added here if needed for the history page itself,
        // but most data will be loaded via JavaScript calling the API.
        return "history"; // This refers to src/main/resources/templates/history.html
    }


    @PostMapping("/api/loadtest/start")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> startLoadTest() {
        logger.info("Received request to start load test.");
        Map<String, Object> response = new HashMap<>();
        try {
            if (loadEmitterService.areEmittersRunning()) {
                response.put("message", "Load test is already running with run ID: " + loadEmitterService.getCurrentRunId());
                response.put("runId", loadEmitterService.getCurrentRunId());
                logger.warn("Attempted to start load test while it was already running.");
                return ResponseEntity.status(409).body(response); // 409 Conflict
            }
            // Generate a unique run ID: timestamp + UUID part for uniqueness
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
            String uniquePart = UUID.randomUUID().toString().substring(0, 8);
            String runId = "run_" + timestamp + "_" + uniquePart;

            loadEmitterService.startEmitters(runId); // Pass runId
            response.put("message", "Load test started successfully with run ID: " + runId);
            response.put("runId", runId);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Error starting load test", e);
            response.put("message", "Error starting load test: " + e.getMessage());
            return ResponseEntity.status(500).body(response);
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
                return ResponseEntity.status(409).body(response);
            }
            loadEmitterService.stopEmitters();

            // Generate final report for this specific run if reporting is enabled
            if (currentRunId != null && summaryReportingService.getProperties().getReporting().getShutdown().isEnabled()) {
                logger.info("Generating final report for manually stopped run ID: {}", currentRunId);
                summaryReportingService.generateAndLogSummaryReport(currentRunId);
            }
            loadEmitterService.finalizeRunSession(); // Clear the runId context in emitter service

            response.put("message", "Load test (run ID: " + currentRunId + ") stopped successfully.");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Error stopping load test for run ID: " + currentRunId, e);
            response.put("message", "Error stopping load test: " + e.getMessage());
            return ResponseEntity.status(500).body(response);
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
            return ResponseEntity.status(500).body(Collections.emptyList());
        }
    }

    @GetMapping(value = "/api/loadtest/isrunning", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<Map<String, Object>> isLoadTestRunning() {
        Map<String, Object> response = new HashMap<>();
        boolean running = loadEmitterService.areEmittersRunning();
        response.put("running", running);
        response.put("currentRunId", running ? loadEmitterService.getCurrentRunId() : null);
        return ResponseEntity.ok(response);
    }

    // --- Endpoints for History and Export ---

    @GetMapping(value = "/api/loadtest/history", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<List<TestHistoryService.TestRunSummary>> getTestHistory() {
        try {
            return ResponseEntity.ok(testHistoryService.listTestRuns());
        } catch (Exception e) {
            logger.error("Error fetching test history list", e);
            return ResponseEntity.status(500).body(Collections.emptyList());
        }
    }

    @GetMapping(value = "/api/loadtest/history/{runId}/details", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<List<SummaryReportingService.TargetReportData>> getTestRunDetails(@PathVariable String runId) {
        try {
            List<SummaryReportingService.TargetReportData> details = testHistoryService.getTestRunDetails(runId);
            if (details == null || details.isEmpty()) { // Check if details could be null
                logger.warn("No details found or error occurred for run ID: {}", runId);
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(details);
        } catch (IOException e) { // Catch specific IOException from getTestRunDetails
            logger.error("IOException fetching details for run ID: {}", runId, e);
            return ResponseEntity.status(500).body(Collections.emptyList()); // Or build appropriate error response
        }
        catch (Exception e) {
            logger.error("Generic error fetching details for run ID: {}", runId, e);
            return ResponseEntity.status(500).body(Collections.emptyList());
        }
    }

    @GetMapping("/api/loadtest/export/{runId}/{format}")
    public ResponseEntity<InputStreamResource> exportTestRun(
            @PathVariable String runId,
            @PathVariable String format) {
        try {
            File fileToExport = null;
            String contentType = MediaType.APPLICATION_OCTET_STREAM_VALUE;
            // Filename for download should be URL encoded or sanitized if runId can have special chars.
            // For simplicity, direct usage here.
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
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment;filename=\"" + filename + "\"") // Added quotes for filename
                    .contentType(MediaType.parseMediaType(contentType))
                    .contentLength(fileToExport.length())
                    .body(resource);

        } catch (FileNotFoundException e) {
            logger.error("File not found for export: run ID {}, format {}", runId, format, e);
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            logger.error("Error exporting test run ID {}: format {}", runId, format, e);
            return ResponseEntity.status(500).build();
        }
    }
}
