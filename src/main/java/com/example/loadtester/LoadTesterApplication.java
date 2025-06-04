package com.example.loadtester;

import com.example.loadtester.config.LoadTesterProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@SpringBootApplication
@EnableScheduling
public class LoadTesterApplication {
    private static final Logger logger = LoggerFactory.getLogger(LoadTesterApplication.class);

    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(LoadTesterApplication.class, args);

        LoadTesterProperties properties = context.getBean(LoadTesterProperties.class);
        int runDurationMinutes = properties.getRunDurationMinutes();

        if (runDurationMinutes > 0) {
            ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r);
                thread.setName("app-shutdown-scheduler");
                thread.setDaemon(true); // Allow JVM to exit if this is the only thread running
                return thread;
            });

            scheduler.schedule(() -> {
                logger.info("Configured run duration of {} minutes reached. Initiating application shutdown.", runDurationMinutes);
                SpringApplication.exit(context, () -> 0); // Exit gracefully
            }, runDurationMinutes, TimeUnit.MINUTES);
            logger.info("Application scheduled to run for {} minutes.", runDurationMinutes);
        } else {
            logger.info("Application configured to run indefinitely (runDurationMinutes is 0 or negative).");
        }
    }
}
    