package com.example.loadtester;

// import com.example.loadtester.config.LoadTesterProperties; // No longer needed here for shutdown
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
// import org.springframework.context.ConfigurableApplicationContext; // No longer needed here
import org.springframework.scheduling.annotation.EnableScheduling;

// import java.util.concurrent.Executors; // No longer needed here
// import java.util.concurrent.ScheduledExecutorService; // No longer needed here
// import java.util.concurrent.TimeUnit; // No longer needed here

@SpringBootApplication
@EnableScheduling // Still useful for @Scheduled tasks like PeriodicReporter
public class LoadTesterApplication {
    private static final Logger logger = LoggerFactory.getLogger(LoadTesterApplication.class);

    public static void main(String[] args) {
        // ConfigurableApplicationContext context = SpringApplication.run(LoadTesterApplication.class, args);
        SpringApplication.run(LoadTesterApplication.class, args); // Just run the application

        // LoadTesterProperties properties = context.getBean(LoadTesterProperties.class);
        // int runDurationMinutes = properties.getRunDurationMinutes();

        // The application-wide shutdown logic based on runDurationMinutes has been removed.
        // The application will now run until manually terminated.
        // The runDurationMinutes will now control the duration of the load emitting phase,
        // managed by LoadEmitterService when started from the UI.

        logger.info("LoadTesterApplication started. It will run until manually stopped.");
        logger.info("The 'runDurationMinutes' property now controls the duration of a load test session initiated via the UI.");
    }
}
