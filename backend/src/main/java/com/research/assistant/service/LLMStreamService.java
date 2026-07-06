package com.research.assistant.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * LLM 流式响应服务 —— 直接向 OutputStream 写入 SSE 格式数据并强制刷新。
 * <p>
 * 相比 {@link org.springframework.web.servlet.mvc.method.annotation.SseEmitter}，
 * 这种方式可以每收到一个 token 就 flush，避免 Tomcat/Spring 的响应缓冲导致前端看不到实时流。
 */
@Service
public class LLMStreamService {

    private static final Logger log = LoggerFactory.getLogger(LLMStreamService.class);

    private final SettingsService settingsService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public LLMStreamService(SettingsService settingsService, ObjectMapper objectMapper) {
        this.settingsService = settingsService;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    /**
     * 向指定输出流推送 LLM 流式响应（SSE 格式）。
     */
    public void streamChat(OutputStream out, String systemPrompt, String userMessage) {
        Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
        try {
            String apiKey = settingsService.getValue("api_key");
            String model = settingsService.getValue("model");
            String baseUrl = settingsService.getValue("base_url");
            if (apiKey == null || apiKey.isBlank() || model == null || model.isBlank()
                    || baseUrl == null || baseUrl.isBlank()) {
                sendEvent(writer, "error", "LLM 配置不完整");
                writer.flush();
                return;
            }

            String url = buildChatUrl(baseUrl);
            double temperature = resolveTemperature(model);
            String requestBody = objectMapper.writeValueAsString(new StreamRequest(
                    model,
                    new Message[]{new Message("system", systemPrompt), new Message("user", userMessage)},
                    temperature,
                    4096
            ));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(Duration.ofSeconds(180))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<java.io.InputStream> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() != 200) {
                String body = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                sendEvent(writer, "error", "HTTP " + response.statusCode() + ": " + body);
                writer.flush();
                return;
            }

            BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("data:")) {
                    String data = line.substring(5).trim();
                    if ("[DONE]".equals(data)) break;
                    try {
                        JsonNode node = objectMapper.readTree(data);
                        JsonNode choices = node.get("choices");
                        if (choices != null && choices.size() > 0) {
                            JsonNode delta = choices.get(0).get("delta");
                            if (delta != null) {
                                // kimi-k2.7-code 等思考模型把流式内容放在 reasoning_content 里
                                String content = null;
                                if (delta.has("content") && !delta.get("content").isNull()) {
                                    content = delta.get("content").asText();
                                }
                                if ((content == null || content.isEmpty()) && delta.has("reasoning_content")
                                        && !delta.get("reasoning_content").isNull()) {
                                    content = delta.get("reasoning_content").asText();
                                }
                                if (content != null && !content.isEmpty()) {
                                    sendEvent(writer, "token", content);
                                    writer.flush();
                                }
                            }
                        }
                    } catch (Exception ignored) {
                        // 忽略无法解析的行
                    }
                }
            }
            sendEvent(writer, "done", "");
            writer.flush();
        } catch (Exception e) {
            log.warn("SSE 流式输出异常: {}", e.getMessage());
            try {
                sendEvent(writer, "error", e.getMessage());
                writer.flush();
            } catch (Exception ignored) {
                // 客户端可能已断开
            }
        }
    }

    private void sendEvent(Writer writer, String eventName, String data) throws IOException {
        writer.write("event:" + eventName + "\n");
        writer.write("data:" + data + "\n\n");
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

    private static class StreamRequest {
        public String model;
        public Message[] messages;
        public double temperature;
        public int max_tokens;
        public boolean stream = true;

        public StreamRequest(String model, Message[] messages, double temperature, int maxTokens) {
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
