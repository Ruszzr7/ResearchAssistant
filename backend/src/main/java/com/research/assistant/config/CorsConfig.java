package com.research.assistant.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Configurable, localhost-first CORS policy for the single-user application. */
@Configuration
public class CorsConfig {

    @Value("${app.http.cors.allowed-origins:http://localhost:5173,http://localhost:5174,http://[::1]:5173,http://[::1]:5174}")
    private String[] allowedOrigins;

    @Value("${app.http.cors.allow-credentials:false}")
    private boolean allowCredentials;

    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/api/**")
                        .allowedOriginPatterns(allowedOrigins)
                        .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                        .allowedHeaders("Content-Type", "Accept", "Idempotency-Key", "X-Request-Id")
                        .exposedHeaders("X-Request-Id")
                        .allowCredentials(allowCredentials)
                        .maxAge(3600);
            }
        };
    }
}
