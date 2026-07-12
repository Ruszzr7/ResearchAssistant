package com.research.assistant.service.rag;

import com.research.assistant.entity.PaperAnalysis;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 论文分块器 —— 将论文原文与结构化分析字段切分为适合向量检索的 chunk。
 */
@Component
public class DocumentChunker {

    /** 每块最大字符数 */
    private static final int MAX_CHUNK_SIZE = 500;
    /** 相邻块重叠字符数 */
    private static final int OVERLAP = 50;

    /**
     * 根据 PaperAnalysis 生成文档块。
     */
    public List<DocumentChunk> chunk(PaperAnalysis analysis) {
        List<DocumentChunk> chunks = new ArrayList<>();
        Long paperId = analysis.getPaperId();

        // 结构化字段作为独立 chunk，优先级高
        addIfPresent(chunks, paperId, "CONTRIBUTION", analysis.getCoreContribution(), "核心贡献");
        addIfPresent(chunks, paperId, "METHOD", analysis.getMethodSummary(), "方法概述");
        addIfPresent(chunks, paperId, "FINDING", jsonToText(analysis.getKeyFindingsJson()), "主要发现");
        addIfPresent(chunks, paperId, "LIMITATION", jsonToText(analysis.getLimitationsJson()), "局限性");
        addIfPresent(chunks, paperId, "DATASET", jsonToText(analysis.getDatasetsJson()), "数据集");

        // 原始 PDF 文本按段落切分
        String rawText = analysis.getRawText();
        if (rawText != null && !rawText.isBlank()) {
            chunks.addAll(splitRawText(paperId, rawText));
        }

        List<DocumentChunk> ordered = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            ordered.add(chunks.get(i).withOrder(i));
        }
        return ordered;
    }

    private void addIfPresent(List<DocumentChunk> chunks, Long paperId, String type, String content, String source) {
        if (content == null || content.isBlank()) {
            return;
        }
        String trimmed = content.trim();
        if (trimmed.length() <= MAX_CHUNK_SIZE) {
            chunks.add(new DocumentChunk(paperId, type, trimmed, source));
            return;
        }
        // 结构化字段也可能较长，按句子切分
        chunks.addAll(splitBySentences(paperId, type, trimmed, source));
    }

    private List<DocumentChunk> splitRawText(Long paperId, String rawText) {
        List<DocumentChunk> chunks = new ArrayList<>();
        // 先按段落拆分
        String normalizedText = rawText.replaceAll("\\s+", " ").trim();
        String[] paragraphs = rawText.split("\\n\\s*\\n");
        StringBuilder buffer = new StringBuilder();
        int partIndex = 0;
        int normalizedOffset = 0;
        for (String para : paragraphs) {
            String cleaned = para.trim().replaceAll("\\s+", " ");
            if (cleaned.isBlank()) continue;

            int startOffset = normalizedText.indexOf(cleaned, Math.max(0, normalizedOffset));
            if (startOffset < 0) {
                startOffset = normalizedOffset;
            }
            normalizedOffset = Math.min(normalizedText.length(), startOffset + cleaned.length());

            if (buffer.length() + cleaned.length() > MAX_CHUNK_SIZE && buffer.length() > 0) {
                String content = buffer.toString().trim();
                int end = Math.min(normalizedText.length(), startOffset);
                int start = Math.max(0, end - content.length());
                chunks.add(new DocumentChunk(paperId, "RAW", content,
                        "PDF 原文 part " + (++partIndex), "PDF_TEXT", null, null, start, end));
                buffer.setLength(0);
                if (cleaned.length() > OVERLAP) {
                    buffer.append(cleaned, 0, OVERLAP).append(" ");
                }
            }

            // 单段过长时直接按窗口切分
            while (cleaned.length() > MAX_CHUNK_SIZE) {
                if (buffer.length() > 0) {
                    String content = buffer.toString().trim();
                    int end = Math.min(normalizedText.length(), startOffset);
                    int start = Math.max(0, end - content.length());
                    chunks.add(new DocumentChunk(paperId, "RAW", content,
                            "PDF 原文 part " + (++partIndex), "PDF_TEXT", null, null, start, end));
                    buffer.setLength(0);
                }
                String window = cleaned.substring(0, MAX_CHUNK_SIZE);
                int windowStart = Math.max(0, startOffset);
                int windowEnd = Math.min(normalizedText.length(), windowStart + window.length());
                chunks.add(new DocumentChunk(paperId, "RAW", window,
                        "PDF 原文 part " + (++partIndex), "PDF_TEXT", null, null, windowStart, windowEnd));
                cleaned = cleaned.substring(MAX_CHUNK_SIZE - OVERLAP);
                startOffset = Math.min(normalizedText.length(), startOffset + MAX_CHUNK_SIZE - OVERLAP);
            }

            buffer.append(cleaned).append(" ");
        }
        if (buffer.length() > 0) {
            String content = buffer.toString().trim();
            int end = Math.min(normalizedText.length(), Math.max(normalizedOffset, content.length()));
            int start = Math.max(0, end - content.length());
            chunks.add(new DocumentChunk(paperId, "RAW", content,
                    "PDF 原文 part " + (++partIndex), "PDF_TEXT", null, null, start, end));
        }
        return chunks;
    }

    private List<DocumentChunk> splitBySentences(Long paperId, String type, String text, String source) {
        List<DocumentChunk> chunks = new ArrayList<>();
        // 简单按句号/问号/感叹号拆分
        String[] sentences = text.split("(?<=[。！？.!?])\\s+");
        StringBuilder buffer = new StringBuilder();
        int partIndex = 0;
        for (String sentence : sentences) {
            if (sentence.isBlank()) continue;
            if (sentence.length() > MAX_CHUNK_SIZE) {
                if (buffer.length() > 0) {
                    chunks.add(new DocumentChunk(paperId, type, buffer.toString().trim(),
                            source + " part " + (++partIndex)));
                    buffer.setLength(0);
                }
                String remaining = sentence.trim();
                while (remaining.length() > MAX_CHUNK_SIZE) {
                    chunks.add(new DocumentChunk(paperId, type,
                            remaining.substring(0, MAX_CHUNK_SIZE), source + " part " + (++partIndex)));
                    remaining = remaining.substring(MAX_CHUNK_SIZE - OVERLAP);
                }
                if (!remaining.isBlank()) {
                    buffer.append(remaining).append(" ");
                }
                continue;
            }
            if (buffer.length() + sentence.length() > MAX_CHUNK_SIZE && buffer.length() > 0) {
                chunks.add(new DocumentChunk(paperId, type, buffer.toString().trim(), source + " part " + (++partIndex)));
                buffer.setLength(0);
            }
            buffer.append(sentence).append(" ");
        }
        if (buffer.length() > 0) {
            chunks.add(new DocumentChunk(paperId, type, buffer.toString().trim(), source + " part " + (++partIndex)));
        }
        return chunks;
    }

    private String jsonToText(String json) {
        if (json == null || json.isBlank() || "[]".equals(json) || "{}".equals(json)) {
            return "";
        }
        // 简单清洗 JSON 标记，保留可读文本
        return json.replace("[", " ")
                .replace("]", " ")
                .replace("{", " ")
                .replace("}", " ")
                .replace("\"", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
