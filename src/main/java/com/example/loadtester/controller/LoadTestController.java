package com.example.loadtester.controller;

import com.example.loadtester.service.LoadEmitterService;
import com.example.loadtester.service.SummaryReportingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.Collections; // Added missing import
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Controller // Use @Controller for Thymeleaf template rendering
public class LoadTestController {

    private static final Logger logger = LoggerFactory.getLogger(LoadTestController.class);

    private final LoadEmitterService loadEmitterService;
    private final SummaryReportingService summaryReportingService;

    @Autowired
    public LoadTestController(LoadEmitterService loadEmitterService, SummaryReportingService summaryReportingService) {
        this.loadEmitterService = loadEmitterService;
        this.summaryReportingService = summaryReportingService;
    }

    /**
     * Serves the main dashboard page.
     * @param model Model for Thymeleaf.
     * @return The name of the dashboard HTML template.
     */
    @GetMapping("/")
    public String dashboard(Model model) {
        model.addAttribute("loadTestActive", loadEmitterService.areEmittersRunning());
        // You can add initial configuration details to the model if needed
        // model.addAttribute("targetsConfig", loadTesterProperties.getTargets());
        logger.info("Serving dashboard page.");
        return "dashboard"; // This refers to src/main/resources/templates/dashboard.html
    }

    /**
     * API endpoint to start the load test.
     * @return ResponseEntity indicating success or failure.
     */
    @PostMapping("/api/loadtest/start")
    @ResponseBody // Indicates the return value should be directly bound to the web response body
    public ResponseEntity<Map<String, String>> startLoadTest() {
        logger.info("Received request to start load test.");
        Map<String, String> response = new HashMap<>();
        try {
            if (loadEmitterService.areEmittersRunning()) {
                response.put("message", "Load test is already running.");
                logger.warn("Attempted to start load test while it was already running.");
                return ResponseEntity.status(409).body(response); // 409 Conflict
            }
            loadEmitterService.startEmitters();
            response.put("message", "Load test started successfully.");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Error starting load test", e);
            response.put("message", "Error starting load test: " + e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    /**
     * API endpoint to stop the load test.
     * @return ResponseEntity indicating success or failure.
     */
    @PostMapping("/api/loadtest/stop")
    @ResponseBody
    public ResponseEntity<Map<String, String>> stopLoadTest() {
        logger.info("Received request to stop load test.");
        Map<String, String> response = new HashMap<>();
        try {
            if (!loadEmitterService.areEmittersRunning()) {
                response.put("message", "Load test is not currently running.");
                logger.warn("Attempted to stop load test while it was not running.");
                return ResponseEntity.status(409).body(response); // 409 Conflict
            }
            loadEmitterService.stopEmitters();
            // Optionally, trigger a final summary report immediately on manual stop
            // summaryReportingService.generateAndLogSummaryReport();
            response.put("message", "Load test stopped successfully.");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Error stopping load test", e);
            response.put("message", "Error stopping load test: " + e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    /**
     * API endpoint to get the current status/metrics of the load test.
     * @return A list of TargetReportData or an error message.
     */
    @GetMapping(value = "/api/loadtest/status", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<List<SummaryReportingService.TargetReportData>> getLoadTestStatus() {
        // No need to log every status request if it's polled frequently
        // logger.debug("Received request for load test status.");
        try {
            List<SummaryReportingService.TargetReportData> metrics = summaryReportingService.getLatestMetricsSnapshot();
            return ResponseEntity.ok(metrics);
        } catch (Exception e) {
            logger.error("Error retrieving load test status", e);
            // Return an empty list with a 500 status if an error occurs
            return ResponseEntity.status(500).body(Collections.emptyList());
        }
    }

    /**
     * API endpoint to check if the load test is currently running.
     * @return A map containing the 'running' status.
     */
    @GetMapping(value = "/api/loadtest/isrunning", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<Map<String, Boolean>> isLoadTestRunning() {
        Map<String, Boolean> response = new HashMap<>();
        response.put("running", loadEmitterService.areEmittersRunning());
        return ResponseEntity.ok(response);
    }
}
