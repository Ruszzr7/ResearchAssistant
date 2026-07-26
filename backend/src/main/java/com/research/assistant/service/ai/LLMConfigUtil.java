package com.research.assistant.service.ai;

/**
 * LLM 配置工具方法 —— 供模型工厂与手动 HTTP 调用共享。
 */
public final class LLMConfigUtil {

    private LLMConfigUtil() {
        // 工具类
    }

    /**
     * 标准化用户填写的 OpenAI-compatible Base URL。
     * <p>版本路径属于供应商契约，不能统一删除或追加 /v1。</p>
     */
    public static String normalizeBaseUrl(String raw) {
        if (raw == null) {
            return "";
        }
        String normalized = raw.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        String endpoint = "/chat/completions";
        if (normalized.endsWith(endpoint)) {
            normalized = normalized.substring(0, normalized.length() - endpoint.length());
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    public static String chatCompletionsUrl(String baseUrl) {
        return normalizeBaseUrl(baseUrl) + "/chat/completions";
    }
}
