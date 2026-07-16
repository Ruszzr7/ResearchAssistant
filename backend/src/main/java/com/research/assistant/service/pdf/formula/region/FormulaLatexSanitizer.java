package com.research.assistant.service.pdf.formula.region;

import java.util.Locale;

final class FormulaLatexSanitizer {

    private FormulaLatexSanitizer() { }

    static String sanitize(String raw) {
        if (raw == null) return "";
        String value = raw.strip();
        value = stripFence(value);
        value = stripDelimiter(value, "$$", "$$");
        value = stripDelimiter(value, "\\[", "\\]");
        value = stripDelimiter(value, "\\(", "\\)");
        value = stripDelimiter(value, "$", "$");
        value = value.strip();
        if (value.length() > 4000) {
            throw new IllegalArgumentException("LaTeX 超过 4000 字符");
        }
        if (value.chars().anyMatch(code -> Character.isISOControl(code)
                && code != '\n' && code != '\r' && code != '\t')) {
            throw new IllegalArgumentException("LaTeX 包含非法控制字符");
        }
        String lowered = value.toLowerCase(Locale.ROOT);
        if (lowered.equals("unknown") || lowered.equals("n/a") || lowered.equals("无法识别")) {
            return "";
        }
        return value;
    }

    private static String stripFence(String value) {
        if (!value.startsWith("```")) return value;
        int firstLine = value.indexOf('\n');
        int closing = value.lastIndexOf("```");
        return firstLine > 0 && closing > firstLine
                ? value.substring(firstLine + 1, closing).strip() : value;
    }

    private static String stripDelimiter(String value, String start, String end) {
        return value.startsWith(start) && value.endsWith(end)
                && value.length() > start.length() + end.length()
                ? value.substring(start.length(), value.length() - end.length()).strip()
                : value;
    }
}
