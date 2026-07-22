package com.research.assistant.service.pdf.math;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** Converts existing Unicode math text to conservative LaTeX without OCR or network calls. */
@Component
@Order(0)
public class LocalUnicodeMathTranscriptionProvider implements InlineMathTranscriptionProvider {

    static final String VERSION = "local-unicode-math-v1";
    private static final Map<String, String> SYMBOLS = symbols();
    private static final Map<Integer, String> SUPERSCRIPTS = Map.ofEntries(
            Map.entry(0x2070, "0"), Map.entry(0x00b9, "1"), Map.entry(0x00b2, "2"),
            Map.entry(0x00b3, "3"), Map.entry(0x2074, "4"), Map.entry(0x2075, "5"),
            Map.entry(0x2076, "6"), Map.entry(0x2077, "7"), Map.entry(0x2078, "8"),
            Map.entry(0x2079, "9"), Map.entry(0x207a, "+"), Map.entry(0x207b, "-"),
            Map.entry(0x207f, "n"));
    private static final Map<Integer, String> SUBSCRIPTS = Map.ofEntries(
            Map.entry(0x2080, "0"), Map.entry(0x2081, "1"), Map.entry(0x2082, "2"),
            Map.entry(0x2083, "3"), Map.entry(0x2084, "4"), Map.entry(0x2085, "5"),
            Map.entry(0x2086, "6"), Map.entry(0x2087, "7"), Map.entry(0x2088, "8"),
            Map.entry(0x2089, "9"), Map.entry(0x208a, "+"), Map.entry(0x208b, "-"));

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public MathTranscriptionCandidate transcribe(MathTranscriptionRequest request) {
        String source = request == null || request.sourceText() == null
                ? "" : request.sourceText().strip();
        if (source.isBlank() || source.indexOf('\ufffd') >= 0) {
            return new MathTranscriptionCandidate(MathTranscriptionStatus.UNAVAILABLE, "", 0,
                    "现有文字层缺少可可靠转写的数学字符");
        }
        if (source.contains("\\frac") || source.contains("\\sum") || source.contains("$$")) {
            return new MathTranscriptionCandidate(MathTranscriptionStatus.READY,
                    stripMathDelimiters(source), 0.92, "复用 PDF 文字层中的 LaTeX");
        }

        String latex = replaceScripts(replaceSymbols(source));
        if (latex.equals(source) && !source.matches(".*[_^=<>].*")) {
            return new MathTranscriptionCandidate(MathTranscriptionStatus.UNAVAILABLE, "", 0.25,
                    "未检测到可确定映射的数学语法");
        }
        return new MathTranscriptionCandidate(MathTranscriptionStatus.APPROXIMATE, latex, 0.74,
                "由 PDF Unicode 文字层本地转换；二维排版需回原页核对");
    }

    private String replaceSymbols(String source) {
        String result = source;
        for (Map.Entry<String, String> entry : SYMBOLS.entrySet()) {
            result = result.replace(entry.getKey(), entry.getValue());
        }
        return result;
    }

    private String replaceScripts(String source) {
        StringBuilder result = new StringBuilder();
        for (int offset = 0; offset < source.length();) {
            int codePoint = source.codePointAt(offset);
            Map<Integer, String> map = SUPERSCRIPTS.containsKey(codePoint) ? SUPERSCRIPTS : SUBSCRIPTS;
            String value = map.get(codePoint);
            if (value == null) {
                result.appendCodePoint(codePoint);
                offset += Character.charCount(codePoint);
                continue;
            }
            boolean superscript = map == SUPERSCRIPTS;
            StringBuilder sequence = new StringBuilder(value);
            offset += Character.charCount(codePoint);
            while (offset < source.length()) {
                int next = source.codePointAt(offset);
                String nextValue = map.get(next);
                if (nextValue == null) break;
                sequence.append(nextValue);
                offset += Character.charCount(next);
            }
            result.append(superscript ? "^{" : "_{").append(sequence).append('}');
        }
        return result.toString();
    }

    private String stripMathDelimiters(String value) {
        return value.replace("$$", "").replaceAll("^\\$|\\$$", "").strip();
    }

    private static Map<String, String> symbols() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("ℂ", "\\mathbb{C}"); values.put("ℝ", "\\mathbb{R}");
        values.put("ℕ", "\\mathbb{N}"); values.put("ℤ", "\\mathbb{Z}");
        values.put("∈", "\\in "); values.put("∉", "\\notin ");
        values.put("∀", "\\forall "); values.put("∃", "\\exists ");
        values.put("≤", "\\le "); values.put("≥", "\\ge ");
        values.put("≠", "\\ne "); values.put("≈", "\\approx ");
        values.put("∑", "\\sum "); values.put("∏", "\\prod ");
        values.put("√", "\\sqrt{}"); values.put("∞", "\\infty ");
        values.put("×", "\\times "); values.put("÷", "\\div ");
        values.put("∥", "\\Vert "); values.put("μ", "\\mu ");
        values.put("Σ", "\\Sigma "); values.put("Δ", "\\Delta ");
        return Map.copyOf(values);
    }
}
