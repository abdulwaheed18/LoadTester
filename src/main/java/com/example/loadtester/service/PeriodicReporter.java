package com.example.loadtester.service;

import com.example.loadtester.config.LoadTesterProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.UUID;

@Component
public class PeriodicReporter {
    private static final Logger logger = LoggerFactory.getLogger(PeriodicReporter.class);
    private final SummaryReportingService summaryReportingService;
    private final LoadTesterProperties properties;
    private final LoadEmitterService loadEmitterService; // Inject to check for active run

    @Autowired
    public PeriodicReporter(SummaryReportingService summaryReportingService,
                            LoadTesterProperties properties,
                            LoadEmitterService loadEmitterService) {
        this.summaryReportingService = summaryReportingService;
        this.properties = properties;
        this.loadEmitterService = loadEmitterService;
    }

    @Scheduled(fixedRateString = "${loadtester.reporting.periodic.summaryIntervalMs:60000}")
    public void reportPeriodically() {
        if (properties.getReporting().getPeriodic().isEnabled()) {
            String runIdToUse;
            // Check if a user-initiated test is currently running
            if (loadEmitterService.areEmittersRunning() && loadEmitterService.getCurrentRunId() != null) {
                runIdToUse = loadEmitterService.getCurrentRunId();
                logger.debug("Periodic summary report triggered by schedule during active run ID: {}.", runIdToUse);
                // In this case, the periodic report will essentially be an interim report for the active run.
            } else {
                // If no active UI-triggered run, generate a unique ID for this periodic report instance.
                String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
                String uniquePart = UUID.randomUUID().toString().substring(0, 4); // Shorter unique part for periodic
                runIdToUse = "periodic_report_" + timestamp + "_" + uniquePart;
                logger.debug("Periodic summary report triggered by schedule. Generating with ID: {}.", runIdToUse);
            }
            summaryReportingService.generateAndLogSummaryReport(runIdToUse);
        } else {
            logger.debug("Periodic summary report is disabled. Skipping scheduled report.");
        }
    }
}
