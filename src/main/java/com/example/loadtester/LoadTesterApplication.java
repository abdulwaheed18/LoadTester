package com.example.loadtester;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling; // Import this

@SpringBootApplication
@EnableScheduling // Add this annotation to enable @Scheduled tasks
public class LoadTesterApplication {
    public static void main(String[] args) {
        SpringApplication.run(LoadTesterApplication.class, args);
    }
}
