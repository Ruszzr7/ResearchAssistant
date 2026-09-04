package com.research.assistant.service.ai;

import com.research.assistant.dto.LlmResponse;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.PdfFileContent;
import dev.langchain4j.data.message.TextContent;

import java.util.List;

/**
 * 单次 LLM 调用的轻量预算策略。
 *
 * <p>当前项目的模型实例统一由 {@code LangChain4jModelFactory} 管理，调用方不能为每次请求
 * 动态创建模型。因此这里先在业务层限制输入、输出和重试次数，避免修复流程无限扩大成本。
 * 后续接入质量事件持久化时，可直接记录该策略和实际 token。</p>
 */
public record LlmCallPolicy(
        String taskType,
        int maxInputChars,
        int maxInputTokens,
        int maxOutputTokens,
        int maxAttempts,
        boolean jsonOutput,
        String reasoningEffort) {

    /** 粗略预留视觉输入预算；具体图片 token 化由 Provider 决定。 */
    private static final int MULTIMODAL_PART_TOKEN_ESTIMATE = 1_024;

    public LlmCallPolicy(String taskType,
                         int maxInputChars,
                         int maxInputTokens,
                         int maxOutputTokens,
                         int maxAttempts,
                         boolean jsonOutput) {
        this(taskType, maxInputChars, maxInputTokens, maxOutputTokens,
                maxAttempts, jsonOutput, null);
    }

    public LlmCallPolicy(String taskType,
                         int maxInputChars,
                         int maxInputTokens,
                         int maxOutputTokens,
                         int maxAttempts) {
        this(taskType, maxInputChars, maxInputTokens, maxOutputTokens,
                maxAttempts, false, null);
    }

    public static final LlmCallPolicy PAPER_ANALYSIS_REPAIR =
            new LlmCallPolicy("paper-analysis-repair", 10_000, 4_096, 2_048, 1, true);

    public LlmCallPolicy {
        if (taskType == null || taskType.isBlank()) {
            throw new IllegalArgumentException("taskType must not be blank");
        }
        if (maxInputChars <= 0 || maxInputTokens <= 0 || maxOutputTokens <= 0 || maxAttempts <= 0) {
            throw new IllegalArgumentException("LLM call budget must be positive");
        }
        reasoningEffort = reasoningEffort == null || reasoningEffort.isBlank()
                ? null : reasoningEffort.trim().toLowerCase(java.util.Locale.ROOT);
        if (reasoningEffort != null
                && !java.util.Set.of("low", "medium", "high", "max").contains(reasoningEffort)) {
            throw new IllegalArgumentException("unsupported reasoning effort");
        }
    }

    /** 以字符数为上限截断输入，避免修复 Prompt 带入整篇论文。 */
    public String limitInput(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxInputChars ? value : value.substring(0, maxInputChars);
    }

    /** 估算 Prompt token；用于日志和预算判断，不替代 Provider 的实际 token 统计。 */
    public int estimatePromptTokens(String systemPrompt, String userMessage) {
        int chars = (systemPrompt == null ? 0 : systemPrompt.length())
                + (userMessage == null ? 0 : userMessage.length());
        return Math.max(0, (chars + 3) / 4);
    }

    public boolean exceedsInputBudget(String systemPrompt, String userMessage) {
        return estimatePromptTokens(systemPrompt, userMessage) > maxInputTokens;
    }

    /**
     * 估算包含文本、图片或文件的实际用户消息。LangChain4j 不暴露 Provider
     * 的图片 token 计算，因此文本按字符估算，视觉内容按 part 预留固定预算。
     */
    public int estimateInputTokens(String systemPrompt, List<? extends Content> userContents) {
        long textChars = textChars(systemPrompt, userContents);
        long visualParts = userContents == null ? 0 : userContents.stream()
                .filter(content -> content instanceof ImageContent || content instanceof PdfFileContent)
                .count();
        long estimate = (textChars + 3) / 4
                + visualParts * MULTIMODAL_PART_TOKEN_ESTIMATE;
        return estimate >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) estimate;
    }

    public boolean exceedsInputBudget(String systemPrompt, List<? extends Content> userContents) {
        return textChars(systemPrompt, userContents) > maxInputChars
                || estimateInputTokens(systemPrompt, userContents) > maxInputTokens;
    }

    private long textChars(String systemPrompt, List<? extends Content> userContents) {
        long chars = systemPrompt == null ? 0 : systemPrompt.length();
        if (userContents == null) return chars;
        for (Content content : userContents) {
            if (content instanceof TextContent textContent && textContent.text() != null) {
                chars += textContent.text().length();
            }
        }
        return chars;
    }

    public boolean exceedsOutputBudget(LlmResponse response) {
        if (response == null) {
            return true;
        }
        Integer completionTokens = response.getCompletionTokens();
        if (completionTokens != null && completionTokens > maxOutputTokens) {
            return true;
        }
        String content = response.getContent();
        return content != null && content.length() > maxOutputTokens * 4;
    }
}
