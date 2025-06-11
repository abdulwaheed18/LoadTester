// src/main/java/com/example/loadtester/dto/StartTestRequest.java
package com.example.loadtester.dto;

import com.example.loadtester.config.LoadTesterProperties;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Data Transfer Object for starting a load test with specific, potentially overridden, parameters.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class StartTestRequest {

    private String baseConfigurationId; // Optional: ID of the saved config this run is based on (for logging/context)
    private int runDurationSeconds;
    private List<LoadTesterProperties.TargetEndpoint> targets; // The actual list of targets to run, with overrides applied

    // Default constructor for Jackson
    public StartTestRequest() {
    }

    public StartTestRequest(String baseConfigurationId, int runDurationSeconds, List<LoadTesterProperties.TargetEndpoint> targets) {
        this.baseConfigurationId = baseConfigurationId;
        this.runDurationSeconds = runDurationSeconds;
        this.targets = targets;
    }

    // Getters and Setters
    public String getBaseConfigurationId() {
        return baseConfigurationId;
    }

    public void setBaseConfigurationId(String baseConfigurationId) {
        this.baseConfigurationId = baseConfigurationId;
    }

    public int getRunDurationSeconds() {
        return runDurationSeconds;
    }

    public void setRunDurationSeconds(int runDurationSeconds) {
        this.runDurationSeconds = runDurationSeconds;
    }

    public List<LoadTesterProperties.TargetEndpoint> getTargets() {
        return targets;
    }

    public void setTargets(List<LoadTesterProperties.TargetEndpoint> targets) {
        this.targets = targets;
    }

    @Override
    public String toString() {
        return "StartTestRequest{" +
                "baseConfigurationId='" + baseConfigurationId + '\'' +
                ", runDurationSeconds=" + runDurationSeconds +
                ", targetsCount=" + (targets != null ? targets.size() : 0) +
                '}';
    }
}