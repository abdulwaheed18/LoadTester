package com.example.loadtester.service;

import com.example.loadtester.config.LoadTesterProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class PeriodicReporter {
    private static final Logger logger = LoggerFactory.getLogger(PeriodicReporter.class);
    private final SummaryReportingService summaryReportingService;
    private final LoadTesterProperties properties;

    public PeriodicReporter(SummaryReportingService summaryReportingService, LoadTesterProperties properties) {
        this.summaryReportingService = summaryReportingService;
        this.properties = properties;
    }

    @Scheduled(fixedRateString = "${loadtester.reporting.periodic.summaryIntervalMs:60000}")
    public void reportPeriodically() {
        if (properties.getReporting().getPeriodic().isEnabled()) {
            logger.debug("Periodic summary report triggered by schedule.");
            summaryReportingService.generateAndLogSummaryReport();
        } else {
            logger.debug("Periodic summary report is disabled. Skipping scheduled report.");
        }
    }
}
