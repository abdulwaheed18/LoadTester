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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
public class SummaryReportingService {
    private static final Logger logger = LoggerFactory.getLogger(SummaryReportingService.class);
    private final MeterRegistry meterRegistry;
    private final LoadTesterProperties properties;
    private final LoadEmitterService loadEmitterService;
    private final TemplateEngine templateEngine;

    public SummaryReportingService(MeterRegistry meterRegistry,
                                   LoadTesterProperties properties,
                                   LoadEmitterService loadEmitterService) {
        this.meterRegistry = meterRegistry;
        this.properties = properties;
        this.loadEmitterService = loadEmitterService;

        ClassLoaderTemplateResolver templateResolver = new ClassLoaderTemplateResolver();
        templateResolver.setSuffix(".html");
        templateResolver.setTemplateMode(TemplateMode.HTML);
        templateResolver.setCharacterEncoding("UTF-8");
        templateResolver.setPrefix("templates/");
        this.templateEngine = new TemplateEngine();
        this.templateEngine.setTemplateResolver(templateResolver);
    }

    public static class TargetReportData {
        public String name, url, method;
        public int desiredTps;
        public double initiatedRequests, throttledRequests, backpressureDroppedRequests, successfulRequests, failedRequests;
        public LatencyData latencyData;
        public Map<String, Double> statusCodeCounts; // Changed to String key for status, Double for count

        public TargetReportData() {
            statusCodeCounts = new TreeMap<>(); // TreeMap to keep status codes sorted
        }
    }

    public static class LatencyData {
        public double avg, max;
        public long count;
        public LatencyData(double avg, double max, long count) {
            this.avg = avg; this.max = max; this.count = count;
        }
    }

    public void generateAndLogSummaryReport() {
        if (properties.getTargets() == null || properties.getTargets().isEmpty()) {
            logger.info("No targets configured. Skipping summary report.");
            return;
        }

        long reportStartTime = System.currentTimeMillis();
        logger.info("Generating Load Test Summary Report...");

        List<TargetReportData> targetReportsData = new ArrayList<>();
        StringBuilder consoleReport = new StringBuilder("\n\n--- Load Test Summary Report (Console) ---\n");
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        consoleReport.append(String.format("Report Time: %s\n", timestamp));
        consoleReport.append("--------------------------------------------------\n");

        for (LoadTesterProperties.TargetEndpoint target : properties.getTargets()) {
            TargetReportData data = new TargetReportData();
            data.name = target.getName();
            data.url = target.getUrl();
            data.method = target.getMethod();
            data.desiredTps = target.getDesiredTps();

            data.initiatedRequests = getCounterValue("loadtester.requests.initiated", "target", data.name);
            data.throttledRequests = getCounterValue("loadtester.requests.throttled", "target", data.name);
            data.backpressureDroppedRequests = getCounterValue("loadtester.requests.backpressure.dropped", "target", data.name);

            List<Tag> successOutcomeTags = List.of(Tag.of("target", data.name), Tag.of("outcome", "success"));
            data.successfulRequests = meterRegistry.find("loadtester.requests.completed").tags(successOutcomeTags).counters()
                    .stream().mapToDouble(Counter::count).sum();

            List<Tag> failureOutcomeTags = List.of(Tag.of("target", data.name), Tag.of("outcome", "failure"));
            data.failedRequests = meterRegistry.find("loadtester.requests.completed").tags(failureOutcomeTags).counters()
                    .stream().mapToDouble(Counter::count).sum();

            // Collect status code counts
            meterRegistry.find("loadtester.requests.by_status").tag("target", data.name).counters().forEach(counter -> {
                String httpStatus = counter.getId().getTag("http_status");
                if (httpStatus != null) {
                    data.statusCodeCounts.put(httpStatus, counter.count());
                }
            });

            Timer latencyTimer = meterRegistry.find("loadtester.request.latency").tag("target", data.name).timer();
            if (latencyTimer != null && latencyTimer.count() > 0) {
                data.latencyData = new LatencyData(
                        latencyTimer.mean(TimeUnit.MILLISECONDS),
                        latencyTimer.max(TimeUnit.MILLISECONDS),
                        latencyTimer.count()
                );
            }
            targetReportsData.add(data);

            consoleReport.append(String.format("\nTarget: %s (URL: %s, Method: %s)\n",
                    data.name, data.url, data.method));
            consoleReport.append(String.format("  Desired TPS: %s\n",
                    data.desiredTps == 0 ? "Max Effort" : data.desiredTps));
            consoleReport.append(String.format("  Requests Initiated: %.0f\n", data.initiatedRequests));
            consoleReport.append(String.format("  Requests Throttled (Rate Limiter): %.0f\n", data.throttledRequests));
            consoleReport.append(String.format("  Requests Dropped (Backpressure): %.0f\n", data.backpressureDroppedRequests));
            consoleReport.append(String.format("  Successful Requests (2xx): %.0f\n", data.successfulRequests));
            consoleReport.append(String.format("  Failed Requests (non-2xx/errors): %.0f\n", data.failedRequests));
            if (data.latencyData != null) {
                consoleReport.append(String.format("  Latency (ms): Avg=%.2f, Max=%.2f, Count=%d\n",
                        data.latencyData.avg, data.latencyData.max, data.latencyData.count));
            } else {
                consoleReport.append("  Latency (ms): No latency data recorded.\n");
            }
            if (!data.statusCodeCounts.isEmpty()) {
                consoleReport.append("  Status Code Counts:\n");
                data.statusCodeCounts.forEach((status, count) ->
                        consoleReport.append(String.format("    %s: %.0f\n", status, count))
                );
            }
            consoleReport.append("--------------------------------------------------\n");
        }
        consoleReport.append("--- End of Console Report ---\n");
        logger.info(consoleReport.toString());

        if (properties.getReporting().getHtml().isEnabled()) {
            generateHtmlReport(targetReportsData, timestamp);
        }

        logger.info("Summary Report generation took {} ms.", (System.currentTimeMillis() - reportStartTime));
    }

    private void generateHtmlReport(List<TargetReportData> targetReportsData, String reportTimestamp) {
        Context context = new Context();
        context.setVariable("reportTimestamp", reportTimestamp);
        context.setVariable("runDurationMinutes", properties.getRunDurationMinutes());
        context.setVariable("targetReports", targetReportsData);

        String reportFilePath = properties.getReporting().getHtml().getFilePath();
        try (FileWriter writer = new FileWriter(reportFilePath)) {
            templateEngine.process("summary-report", context, writer);
            logger.info("HTML summary report generated successfully at: {}", Paths.get(reportFilePath).toAbsolutePath());
        } catch (IOException e) {
            logger.error("Failed to write HTML summary report to file: {}", reportFilePath, e);
        } catch (Exception e) {
            logger.error("Error generating HTML report", e);
        }
    }

    private double getCounterValue(String name, String... tags) {
        Counter counter = meterRegistry.find(name).tags(tags).counter();
        return (counter != null) ? counter.count() : 0.0;
    }

    @PreDestroy
    public void onShutdown() {
        logger.info("Application shutting down. Stopping emitters and generating final summary report...");
        loadEmitterService.stopAllEmitters();
        if (properties.getReporting().getShutdown().isEnabled()) {
            generateAndLogSummaryReport();
        } else {
            logger.info("Shutdown summary report is disabled via configuration.");
        }
    }
}
