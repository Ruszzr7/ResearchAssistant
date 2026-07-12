package com.research.assistant.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.common.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.concurrent.Semaphore;

/** Single-node guard for expensive synchronous AI/SSE requests. */
@Component
public class AiRequestConcurrencyInterceptor implements HandlerInterceptor {

    private static final String ACQUIRED = AiRequestConcurrencyInterceptor.class.getName() + ".acquired";

    private final Semaphore permits;
    private final ObjectMapper objectMapper;

    public AiRequestConcurrencyInterceptor(
            @Value("${app.http.ai-max-concurrency:4}") int maxConcurrency,
            ObjectMapper objectMapper) {
        if (maxConcurrency < 1 || maxConcurrency > 64) {
            throw new IllegalArgumentException("app.http.ai-max-concurrency 必须在 1-64 之间");
        }
        this.permits = new Semaphore(maxConcurrency);
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (permits.tryAcquire()) {
            request.setAttribute(ACQUIRED, Boolean.TRUE);
            return true;
        }
        response.setStatus(429);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(),
                Result.error(429, "AI 请求并发已达上限，请稍后重试"));
        return false;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        if (Boolean.TRUE.equals(request.getAttribute(ACQUIRED))) {
            permits.release();
        }
    }
}
