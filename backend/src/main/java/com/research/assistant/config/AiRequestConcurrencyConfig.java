package com.research.assistant.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Registers the bounded single-node AI request guard. */
@Configuration
public class AiRequestConcurrencyConfig implements WebMvcConfigurer {

    private final AiRequestConcurrencyInterceptor interceptor;

    public AiRequestConcurrencyConfig(AiRequestConcurrencyInterceptor interceptor) {
        this.interceptor = interceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor).addPathPatterns(
                "/api/research-automation/gap/stream",
                "/api/research-automation/process/*/stream",
                "/api/writing/outline",
                "/api/writing/related-work",
                "/api/writing/citation-check"
        );
    }
}
