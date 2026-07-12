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
                "/api/agent/chat",
                "/api/agent/gap/chat",
                "/api/agent/gap/stream",
                "/api/agent/process/*/stream",
                "/api/writing/outline",
                "/api/writing/related-work",
                "/api/writing/citation-check"
        );
    }
}
