// src/main/java/com/example/loadtester/service/TestConfigurationService.java
package com.example.loadtester.service;

import com.example.loadtester.config.LoadTesterProperties;
import com.example.loadtester.model.TestConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class TestConfigurationService {
    private static final Logger logger = LoggerFactory.getLogger(TestConfigurationService.class);
    private final ObjectMapper objectMapper;
    private final Path configurationsDirectoryPath;

    public TestConfigurationService(ObjectMapper objectMapper,
                                    @Value("${loadtester.configurations.directory:./test_configs}") String configsDir) {
        this.objectMapper = objectMapper.copy();
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
        this.configurationsDirectoryPath = Paths.get(configsDir);
    }

    @PostConstruct
    private void init() {
        try {
            Files.createDirectories(configurationsDirectoryPath);
            logger.info("Test configurations directory initialized at: {}", configurationsDirectoryPath.toAbsolutePath());
        } catch (IOException e) {
            logger.error("Could not create test configurations directory: {}", configurationsDirectoryPath.toAbsolutePath(), e);
        }
    }

    private Path getFilePathForConfig(String configId) {
        String sanitizedId = configId.replaceAll("[^a-zA-Z0-9\\-]", "_");
        return configurationsDirectoryPath.resolve(sanitizedId + ".json");
    }

    public TestConfiguration saveConfiguration(TestConfiguration config) throws IOException {
        if (config.getId() == null || config.getId().trim().isEmpty()) {
            config.setId(UUID.randomUUID().toString());
        }
        Path filePath = getFilePathForConfig(config.getId());
        try {
            objectMapper.writeValue(filePath.toFile(), config);
            logger.info("Saved test configuration '{}' (ID: {}) to {}", config.getName(), config.getId(), filePath);
            return config;
        } catch (IOException e) {
            logger.error("Failed to save test configuration '{}' (ID: {}) to {}", config.getName(), config.getId(), filePath, e);
            throw e;
        }
    }

    public Optional<TestConfiguration> getConfigurationById(String configId) {
        Path filePath = getFilePathForConfig(configId);
        if (!Files.exists(filePath)) {
            return Optional.empty();
        }
        try {
            TestConfiguration config = objectMapper.readValue(filePath.toFile(), TestConfiguration.class);
            return Optional.of(config);
        } catch (IOException e) {
            logger.error("Failed to read test configuration with ID: {} from {}", configId, filePath, e);
            return Optional.empty();
        }
    }

    public List<TestConfiguration> getAllConfigurations() {
        if (!Files.isDirectory(configurationsDirectoryPath)) {
            logger.warn("Configurations directory {} does not exist or is not a directory.", configurationsDirectoryPath);
            return Collections.emptyList();
        }
        try (Stream<Path> paths = Files.list(configurationsDirectoryPath)) {
            return paths
                    .filter(path -> path.toString().endsWith(".json"))
                    .map(path -> {
                        try {
                            return objectMapper.readValue(path.toFile(), TestConfiguration.class);
                        } catch (IOException e) {
                            logger.error("Failed to read configuration file: {}", path, e);
                            return null;
                        }
                    })
                    .filter(java.util.Objects::nonNull)
                    .collect(Collectors.toList());
        } catch (IOException e) {
            logger.error("Failed to list configuration files in directory: {}", configurationsDirectoryPath, e);
            return Collections.emptyList();
        }
    }

    public boolean deleteConfiguration(String configId) {
        Path filePath = getFilePathForConfig(configId);
        try {
            boolean deleted = Files.deleteIfExists(filePath);
            if (deleted) {
                logger.info("Deleted test configuration with ID: {}", configId);
            } else {
                logger.warn("Test configuration with ID: {} not found for deletion.", configId);
            }
            return deleted;
        } catch (IOException e) {
            logger.error("Failed to delete test configuration with ID: {}", configId, e);
            return false;
        }
    }

    public LoadTesterProperties convertToLoadTesterProperties(TestConfiguration userConfig, LoadTesterProperties baseProperties) {
        LoadTesterProperties dynamicProps = new LoadTesterProperties();

        if (baseProperties != null && baseProperties.getReporting() != null) {
            dynamicProps.setReporting(baseProperties.getReporting());
        } else {
            dynamicProps.setReporting(new LoadTesterProperties.Reporting());
        }

        dynamicProps.setRunDurationSeconds(userConfig.getRunDurationSeconds());

        if (userConfig.getTargets() != null) {
            List<LoadTesterProperties.TargetEndpoint> propertyTargets = userConfig.getTargets().stream()
                    .map(tc -> {
                        LoadTesterProperties.TargetEndpoint propEp = new LoadTesterProperties.TargetEndpoint();
                        propEp.setName(tc.getName());
                        propEp.setUrl(tc.getUrl());
                        propEp.setMethod(tc.getMethod());
                        propEp.setHeaders(tc.getHeaders());
                        propEp.setPayloadPath(tc.getPayloadPath());
                        propEp.setDesiredTps(tc.getDesiredTps());
                        propEp.setThrottleIntervalMs(tc.getThrottleIntervalMs());
                        return propEp;
                    }).collect(Collectors.toList());
            dynamicProps.setTargets(propertyTargets);
        }
        return dynamicProps;
    }
}