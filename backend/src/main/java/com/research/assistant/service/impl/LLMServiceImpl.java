package com.research.assistant.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.dto.LlmResponse;
import com.research.assistant.service.LLMService;
import com.research.assistant.service.LLMStreamService;
import com.research.assistant.service.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * DeepSeek API 调用实现。
 * <p>
 * 每次调用从 SettingsService 读取最新 Key，不做本地缓存——支持用户在
 * 设置页修改 Key 后立即生效，无需重启后端。
 */
@Service
public class LLMServiceImpl implements LLMService {

    private static final Logger log = LoggerFactory.getLogger(LLMServiceImpl.class);

    private final SettingsService settingsService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final LLMStreamService streamService;

    public LLMServiceImpl(SettingsService settingsService, ObjectMapper objectMapper,
                          LLMStreamService streamService) {
        this.settingsService = settingsService;
        this.objectMapper = objectMapper;
        this.streamService = streamService;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    @Override
    public String chat(String systemPrompt, String userMessage) {
        return chatWithUsage(systemPrompt, userMessage).getContent();
    }

    @Override
    public LlmResponse chatWithUsage(String systemPrompt, String userMessage) {
        String apiKey = settingsService.getValue("api_key");
        if (apiKey == null || apiKey.isBlank()) {
            throw new RuntimeException("API Key 未配置，请在设置页面填写");
        }

        String model = settingsService.getValue("model");
        if (model == null || model.isBlank()) {
            throw new RuntimeException("模型未配置，请在设置页面填写");
        }

        String baseUrl = settingsService.getValue("base_url");
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new RuntimeException("Base URL 未配置，请在设置页面填写");
        }
        String url = buildChatUrl(baseUrl);

        try {
            // 构建 OpenAI 兼容格式的请求体
            // kimi-k2.7-code 要求 temperature 固定为 1.0
            double temperature = resolveTemperature(model);
            String requestBody = objectMapper.writeValueAsString(new DeepSeekRequest(
                    model,
                    new Message[]{
                            new Message("system", systemPrompt),
                            new Message("user", userMessage)
                    },
                    temperature,
                    4096
            ));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(Duration.ofSeconds(120))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                String errorBody = response.body();
                log.error("DeepSeek API 返回 {} : {}", response.statusCode(), errorBody);
                throw new RuntimeException("API 调用失败 (HTTP " + response.statusCode() + "): " + errorBody);
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode choices = root.get("choices");
            if (choices == null || !choices.isArray() || choices.size() == 0) {
                throw new RuntimeException("API 返回了空的 choices");
            }

            String content = choices.get(0).get("message").get("content").asText();

            // 记录 token 消耗
            JsonNode usage = root.get("usage");
            int promptTokens = 0;
            int completionTokens = 0;
            int totalTokens = 0;
            if (usage != null) {
                promptTokens = usage.get("prompt_tokens").asInt();
                completionTokens = usage.get("completion_tokens").asInt();
                totalTokens = usage.get("total_tokens").asInt();
                log.info("Token 消耗 — prompt: {}, completion: {}, total: {}", promptTokens, completionTokens, totalTokens);
            }

            return new LlmResponse(content, promptTokens, completionTokens, totalTokens);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("API 调用被中断", e);
        } catch (IOException e) {
            throw new RuntimeException("API 调用网络异常: " + e.getMessage(), e);
        }
    }

    @Override
    public StreamingResponseBody chatStream(String systemPrompt, String userMessage) {
        return out -> streamService.streamChat(out, systemPrompt, userMessage);
    }

    // ========== URL 规范化 ==========

    /**
     * 兼容用户填写带或不带 /v1、带或不带末尾斜杠的 Base URL，
     * 统一输出 {base}/v1/chat/completions，避免重复 /v1。
     */
    private String buildChatUrl(String baseUrl) {
        String normalized = baseUrl.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.endsWith("/v1")) {
            normalized = normalized.substring(0, normalized.length() - 3);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized + "/v1/chat/completions";
    }

    /**
     * 根据模型名决定可用采样参数。
     * kimi-k2.7-code 强制 temperature=1.0，其他模型可自由调整。
     */
    private double resolveTemperature(String model) {
        if (model != null && model.toLowerCase().contains("kimi-k2.7-code")) {
            return 1.0;
        }
        return 0.3;
    }

    // ========== DeepSeek API 请求/响应模型（内部类） ==========

    /** OpenAI 兼容的 Chat Completion 请求体 */
    private static class DeepSeekRequest {
        public String model;
        public Message[] messages;
        public double temperature;
        public int max_tokens;

        public DeepSeekRequest(String model, Message[] messages, double temperature, int maxTokens) {
            this.model = model;
            this.messages = messages;
            this.temperature = temperature;
            this.max_tokens = maxTokens;
        }
    }

    private static class Message {
        public String role;
        public String content;

        public Message(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }
}
