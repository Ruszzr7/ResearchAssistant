package com.research.assistant.common;

/**
 * 简单 JSON / Markdown 文本工具。
 */
public final class JsonUtils {

    private JsonUtils() {}

    /**
     * 去除 LLM 输出外层的 markdown 代码块标记，保留内部 JSON 文本。
     */
    public static String extractJson(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (s.startsWith("```")) {
            int i = s.indexOf('\n');
            int j = s.lastIndexOf("```");
            if (i > 0 && j > i) {
                s = s.substring(i + 1, j).trim();
            }
        }
        return s;
    }
}
