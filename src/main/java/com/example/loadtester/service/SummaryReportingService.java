package com.example.loadtester.service;

import com.example.loadtester.config.LoadTesterProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
public class SummaryReportingService {
    private static final Logger logger = LoggerFactory.getLogger(SummaryReportingService.class);
    private final MeterRegistry meterRegistry;
    private final LoadTesterProperties properties;
    private final LoadEmitterService loadEmitterService; // To stop emitters on shutdown

    public SummaryReportingService(MeterRegistry meterRegistry, LoadTesterProperties properties, LoadEmitterService loadEmitterService) {
        this.meterRegistry = meterRegistry;
        this.properties = properties;
        this.loadEmitterService = loadEmitterService;
    }

    public void generateAndLogSummaryReport() {
        if (properties.getTargets() == null || properties.getTargets().isEmpty()) {
            logger.info("No targets configured. Skipping summary report.");
            return;
        }

        long reportStartTime = System.currentTimeMillis();
        logger.info("Generating Load Test Summary Report...");

        StringBuilder report = new StringBuilder("\n\n--- Load Test Summary Report ---\n");
        report.append(String.format("Report Time: %s\n", java.time.LocalDateTime.now()));
        report.append("--------------------------------------------------\n");

        for (LoadTesterProperties.TargetEndpoint target : properties.getTargets()) {
            String targetName = target.getName();
            report.append(String.format("\nTarget: %s (URL: %s, Method: %s)\n",
                    targetName, target.getUrl(), target.getMethod()));
            report.append(String.format("  Desired TPS: %s\n",
                    target.getDesiredTps() == 0 ? "Max Effort" : target.getDesiredTps()));

            // Fetch metrics
            double initiated = getCounterValue("loadtester.requests.initiated", "target", targetName);
            double throttled = getCounterValue("loadtester.requests.throttled", "target", targetName);
            double backpressureDropped = getCounterValue("loadtester.requests.backpressure.dropped", "target", targetName);

            report.append(String.format("  Requests Initiated: %.0f\n", initiated));
            report.append(String.format("  Requests Throttled (by RateLimiter): %.0f\n", throttled));
            report.append(String.format("  Requests Dropped (by Backpressure): %.0f\n", backpressureDropped));


            List<Tag> successTags = new ArrayList<>();
            successTags.add(Tag.of("target", targetName));
            successTags.add(Tag.of("outcome", "success"));
            // Iterate over possible HTTP status codes for successes if needed, or sum them up.
            // For simplicity, summing all successful outcomes:
            double successful = meterRegistry.find("loadtester.requests.completed").tags(successTags).counters()
                    .stream().mapToDouble(Counter::count).sum();
            report.append(String.format("  Successful Requests (Sum of 2xx): %.0f\n", successful));


            List<Tag> failureTags = new ArrayList<>();
            failureTags.add(Tag.of("target", targetName));
            failureTags.add(Tag.of("outcome", "failure"));
            // Sum all failed outcomes:
            double failed = meterRegistry.find("loadtester.requests.completed").tags(failureTags).counters()
                    .stream().mapToDouble(Counter::count).sum();
            report.append(String.format("  Failed Requests (Sum of non-2xx/errors): %.0f\n", failed));


            Timer latencyTimer = meterRegistry.find("loadtester.request.latency").tag("target", targetName).timer();
            if (latencyTimer != null) {
                report.append(String.format("  Latency (ms): Avg=%.2f, Max=%.2f, Count=%.0f\n",
                        latencyTimer.mean(TimeUnit.MILLISECONDS),
                        latencyTimer.max(TimeUnit.MILLISECONDS),
                        (double)latencyTimer.count()
                ));
                // For percentiles, you'd query Prometheus or use specific Micrometer features if pre-computed
                // e.g., latencyTimer.percentile(0.95, TimeUnit.MILLISECONDS) if available and configured
            } else {
                report.append("  Latency (ms): No latency data recorded.\n");
            }
            report.append("--------------------------------------------------\n");
        }
        report.append("--- End of Report ---\n");
        logger.info(report.toString());
        logger.info("Summary Report generation took {} ms.", (System.currentTimeMillis() - reportStartTime));
    }

    private double getCounterValue(String name, String... tags) {
        Counter counter = meterRegistry.find(name).tags(tags).counter();
        return (counter != null) ? counter.count() : 0.0;
    }

    @PreDestroy
    public void onShutdown() {
        logger.info("Application shutting down. Stopping emitters and generating final summary report...");
        loadEmitterService.stopAllEmitters(); // Ensure emitters are stopped
        if (properties.getReporting().getShutdown().isEnabled()) {
            generateAndLogSummaryReport();
        } else {
            logger.info("Shutdown summary report is disabled via configuration.");
        }
    }
}
