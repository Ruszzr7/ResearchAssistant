package com.research.assistant.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.dto.LlmResponse;
import com.research.assistant.service.ai.LLMConfigUtil;
import com.research.assistant.service.ai.LangChain4jModelFactory;
import com.research.assistant.service.ai.LlmCallPolicy;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.TokenUsage;
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
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * LLM 流式响应服务 —— 优先基于 LangChain4j StreamingChatModel，对不兼容的流式响应回退到手动 SSE 解析。
 * <p>
 * 直接向 OutputStream 写入 SSE 格式数据并强制刷新，每收到一个 token 即推送，
 * 保持与前端原有的 `event:token` / `event:done` / `event:error` 协议一致。
 */
@Service
public class LLMStreamService {

    private static final Logger log = LoggerFactory.getLogger(LLMStreamService.class);

    private final LangChain4jModelFactory modelFactory;
    private final SettingsService settingsService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public LLMStreamService(LangChain4jModelFactory modelFactory, SettingsService settingsService,
                            ObjectMapper objectMapper) {
        this.modelFactory = modelFactory;
        this.settingsService = settingsService;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    /**
     * 向指定输出流推送 LLM 流式响应（SSE 格式）。
     * <p>
     * 策略：
     * <ul>
     *   <li>Kimi 系列模型直接走手动 SSE 解析，避免其 reasoning_content 片段导致 LangChain4j 解析失败。</li>
     *   <li>其他模型优先使用 LangChain4j StreamingChatModel；失败时回退到手动 SSE。</li>
     * </ul>
     */
    public void streamChat(OutputStream out, String systemPrompt, String userMessage) {
        if (isKimiModel()) {
            log.debug("检测到 Kimi 模型，直接使用手动 SSE 流式解析");
            streamManually(out, systemPrompt, userMessage);
            return;
        }

        try {
            streamWithLangChain4j(out, systemPrompt, userMessage);
        } catch (Exception e) {
            // 某些 Provider 返回的流式 JSON 片段 LangChain4j 无法解析，回退到手动 SSE 解析。
            log.warn("LangChain4j streaming fallback failed type={}", e.getClass().getSimpleName());
            streamManually(out, systemPrompt, userMessage);
        }
    }

    private boolean isKimiModel() {
        String model = settingsService.getValue("model");
        return model != null && model.toLowerCase().contains("kimi");
    }

    /** Kimi coding/thinking endpoints accept a native switch that removes hidden reasoning. */
    public boolean supportsNativeStructuredOutput() {
        String model = settingsService.getValue("model");
        String baseUrl = settingsService.getValue("base_url");
        if (model == null || baseUrl == null) return false;
        String normalizedModel = model.toLowerCase(Locale.ROOT);
        String normalizedBase = baseUrl.toLowerCase(Locale.ROOT);
        boolean kimiProvider = normalizedModel.contains("kimi")
                || normalizedBase.contains("kimi.com")
                || normalizedBase.contains("moonshot.");
        return kimiProvider && (normalizedBase.contains("/coding/")
                || normalizedModel.contains("-code")
                || normalizedModel.contains("-thinking"));
    }

    /**
     * Executes bounded JSON extraction through the provider-compatible endpoint.
     * This narrow path exists because LangChain4j 1.15 serializes unknown request
     * parameters as a nested object instead of the top-level Kimi `thinking` field.
     */
    public LlmResponse chatStructuredJson(String systemPrompt,
                                          String userMessage,
                                          byte[] imageBytes,
                                          String mimeType,
                                          LlmCallPolicy policy) {
        try {
            String apiKey = requiredSetting("api_key", "API Key");
            String model = requiredSetting("model", "模型");
            String baseUrl = requiredSetting("base_url", "Base URL");
            String url = LLMConfigUtil.normalizeBaseUrl(baseUrl) + "/v1/chat/completions";

            Map<String, Object> requestBody = new LinkedHashMap<>();
            requestBody.put("model", model);
            requestBody.put("messages", structuredMessages(
                    systemPrompt, userMessage, imageBytes, mimeType));
            requestBody.put("max_tokens", policy.maxOutputTokens());
            requestBody.put("response_format", Map.of("type", "json_object"));
            requestBody.put("thinking", Map.of("type", "disabled"));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(Duration.ofSeconds(90))
                    .POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(requestBody)))
                    .build();
            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                log.warn("event=ai_structured_chat_failed status={}", response.statusCode());
                throw new IllegalStateException(
                        "结构化模型服务暂时不可用 (HTTP " + response.statusCode() + ")");
            }
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode choice = root.path("choices").path(0);
            JsonNode usage = root.path("usage");
            String content = choice.path("message").path("content").asText("");
            int promptTokens = usage.path("prompt_tokens").asInt(0);
            int completionTokens = usage.path("completion_tokens").asInt(0);
            int totalTokens = usage.path("total_tokens")
                    .asInt(promptTokens + completionTokens);
            String finishReason = choice.path("finish_reason")
                    .asText("").toUpperCase(Locale.ROOT);
            log.info("event=ai_structured_chat_completed inputTokens={} outputTokens={} "
                            + "totalTokens={} finishReason={}",
                    promptTokens, completionTokens, totalTokens, finishReason);
            return new LlmResponse(
                    content, promptTokens, completionTokens, totalTokens, finishReason);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("结构化模型调用已中断", exception);
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("结构化模型调用失败", exception);
        }
    }

    private List<Map<String, Object>> structuredMessages(String systemPrompt,
                                                         String userMessage,
                                                         byte[] imageBytes,
                                                         String mimeType) {
        Map<String, Object> system = Map.of(
                "role", "system", "content", systemPrompt == null ? "" : systemPrompt);
        if (imageBytes == null || imageBytes.length == 0) {
            return List.of(system, Map.of(
                    "role", "user", "content", userMessage == null ? "" : userMessage));
        }
        String safeMimeType = mimeType == null || mimeType.isBlank()
                ? "image/png" : mimeType;
        String imageUrl = "data:" + safeMimeType + ";base64,"
                + Base64.getEncoder().encodeToString(imageBytes);
        Map<String, Object> user = Map.of(
                "role", "user",
                "content", List.of(
                        Map.of("type", "text", "text",
                                userMessage == null ? "" : userMessage),
                        Map.of("type", "image_url",
                                "image_url", Map.of("url", imageUrl))));
        return List.of(system, user);
    }

    private String requiredSetting(String key, String label) {
        String value = settingsService.getValue(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(label + " 未配置");
        }
        return value;
    }

    /** 使用 LangChain4j StreamingChatModel 输出流式响应。 */
    private void streamWithLangChain4j(OutputStream out, String systemPrompt, String userMessage) throws Exception {
        Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
        StreamingChatModel model = modelFactory.createStreamingModel();

        CountDownLatch latch = new CountDownLatch(1);
        StringBuilder fullContent = new StringBuilder();
        boolean[] outputClosed = { false };
        Throwable[] errorHolder = { null };

        model.chat(
                List.of(SystemMessage.from(systemPrompt), UserMessage.from(userMessage)),
                new StreamingChatResponseHandler() {
                    @Override
                    public void onPartialResponse(String partialResponse) {
                        if (outputClosed[0]) return;
                        try {
                            if (partialResponse != null && !partialResponse.isEmpty()) {
                                fullContent.append(partialResponse);
                                sendEvent(writer, "token", partialResponse);
                                writer.flush();
                            }
                        } catch (Exception e) {
                            outputClosed[0] = true;
                            log.warn("SSE token write failed type={}", e.getClass().getSimpleName());
                        }
                    }

                    @Override
                    public void onCompleteResponse(ChatResponse response) {
                        if (outputClosed[0]) {
                            latch.countDown();
                            return;
                        }
                        try {
                            TokenUsage usage = response != null ? response.tokenUsage() : null;
                            if (usage != null) {
                                log.info("流式调用 Token 消耗 — prompt: {}, completion: {}, total: {}",
                                        usage.inputTokenCount(), usage.outputTokenCount(), usage.totalTokenCount());
                            }
                            String fullText = response != null && response.aiMessage() != null
                                    ? response.aiMessage().text() : null;
                            if (fullText != null && !fullText.equals(fullContent.toString())) {
                                sendEvent(writer, "token", fullText);
                                writer.flush();
                            }
                            sendEvent(writer, "done", "");
                            writer.flush();
                        } catch (Exception e) {
                            log.warn("SSE done write failed type={}", e.getClass().getSimpleName());
                        } finally {
                            latch.countDown();
                        }
                    }

                    @Override
                    public void onError(Throwable error) {
                        log.warn("LangChain4j 流式调用报告错误: {}", error.getMessage());
                        errorHolder[0] = error;
                        outputClosed[0] = true;
                        latch.countDown();
                    }
                }
        );

        awaitLatch(latch);

        // 如果 LangChain4j 一条 token 都没能成功输出就失败，抛给上层走手动回退
        if (errorHolder[0] != null && fullContent.isEmpty()) {
            throw new RuntimeException("LangChain4j 流式调用未输出任何 token 即失败", errorHolder[0]);
        }
    }

    /** 手动 SSE 解析 —— 与旧版实现一致，对 Provider 特殊格式更宽容。 */
    private void streamManually(OutputStream out, String systemPrompt, String userMessage) {
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

            String url = LLMConfigUtil.normalizeBaseUrl(baseUrl) + "/v1/chat/completions";
            double temperature = LLMConfigUtil.resolveTemperature(model);
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
                response.body().close();
                sendEvent(writer, "error", "上游模型服务暂时不可用 (HTTP " + response.statusCode() + ")");
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
                        // 忽略无法解析的行，继续下一条
                    }
                }
            }
            sendEvent(writer, "done", "");
            writer.flush();
        } catch (Exception e) {
            log.warn("manual SSE stream failed type={}", e.getClass().getSimpleName());
            try {
                sendEvent(writer, "error", "上游模型服务暂时不可用，请稍后重试");
                writer.flush();
            } catch (Exception ignored) {
                // 客户端可能已断开
            }
        }
    }

    private void awaitLatch(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void sendEvent(Writer writer, String eventName, String data) throws IOException {
        writer.write("event:" + eventName + "\n");
        writer.write("data:" + data + "\n\n");
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
