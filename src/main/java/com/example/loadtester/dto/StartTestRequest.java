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
    private int runDurationMinutes;
    private List<LoadTesterProperties.TargetEndpoint> targets; // The actual list of targets to run, with overrides applied

    // Default constructor for Jackson
    public StartTestRequest() {
    }

    public StartTestRequest(String baseConfigurationId, int runDurationMinutes, List<LoadTesterProperties.TargetEndpoint> targets) {
        this.baseConfigurationId = baseConfigurationId;
        this.runDurationMinutes = runDurationMinutes;
        this.targets = targets;
    }

    // Getters and Setters
    public String getBaseConfigurationId() {
        return baseConfigurationId;
    }

    public void setBaseConfigurationId(String baseConfigurationId) {
        this.baseConfigurationId = baseConfigurationId;
    }

    public int getRunDurationMinutes() {
        return runDurationMinutes;
    }

    public void setRunDurationMinutes(int runDurationMinutes) {
        this.runDurationMinutes = runDurationMinutes;
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
                ", runDurationMinutes=" + runDurationMinutes +
                ", targetsCount=" + (targets != null ? targets.size() : 0) +
                '}';
    }
}
