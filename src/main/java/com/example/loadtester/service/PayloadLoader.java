package com.example.loadtester.service;

import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import java.nio.charset.StandardCharsets;

@Service
public class PayloadLoader {
    private final ResourceLoader resourceLoader;

    public PayloadLoader(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    public String loadPayload(String location) {
        try {
            Resource res = resourceLoader.getResource(location);
            return StreamUtils.copyToString(res.getInputStream(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load payload from " + location, e);
        }
    }
}
