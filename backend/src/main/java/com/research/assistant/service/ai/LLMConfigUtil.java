package com.research.assistant.service.ai;

/**
 * LLM 配置工具方法 —— 供模型工厂与手动 HTTP 调用共享。
 */
public final class LLMConfigUtil {

    private LLMConfigUtil() {
        // 工具类
    }

    /**
     * 解析 temperature。
     * <p>kimi-k2.7-code 官方要求 temperature 固定为 1.0，其余模型使用 0.3。</p>
     */
    public static double resolveTemperature(String model) {
        if (model != null && model.toLowerCase().contains("kimi-k2.7-code")) {
            return 1.0;
        }
        return 0.3;
    }

    /**
     * 标准化 Base URL：去掉末尾斜杠和重复的 /v1。
     * <p>调用方按需自行追加 /v1 或 /v1/chat/completions。</p>
     */
    public static String normalizeBaseUrl(String raw) {
        if (raw == null) {
            return "";
        }
        String normalized = raw.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.endsWith("/v1")) {
            normalized = normalized.substring(0, normalized.length() - 3);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
