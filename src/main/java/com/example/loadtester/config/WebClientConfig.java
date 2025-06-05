// src/main/java/com/example/loadtester/config/WebClientConfig.java
package com.example.loadtester.config;

import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import javax.net.ssl.SSLException;

@Configuration
public class WebClientConfig {

    private static final Logger logger = LoggerFactory.getLogger(WebClientConfig.class);

    @Bean
    public WebClient.Builder webClientBuilder(LoadTesterProperties properties) throws SSLException {
        HttpClient httpClient;
        if (properties.getHttp().getSsl().isInsecureSkipVerify()) {
            logger.warn("****************************************************************************");
            logger.warn("WARNING: SSL certificate validation is DISABLED globally for WebClient.");
            logger.warn("This should ONLY be used in trusted development/testing environments.");
            logger.warn("DO NOT USE THIS CONFIGURATION IN PRODUCTION for external services.");
            logger.warn("****************************************************************************");

            SslContext sslContext = SslContextBuilder
                    .forClient()
                    .trustManager(InsecureTrustManagerFactory.INSTANCE)
                    .build();
            httpClient = HttpClient.create().secure(t -> t.sslContext(sslContext));
        } else {
            httpClient = HttpClient.create(); // Default HttpClient with SSL validation
        }

        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient));
    }
}
