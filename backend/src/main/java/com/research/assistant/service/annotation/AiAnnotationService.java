package com.research.assistant.service.annotation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.dto.AnnotationDto;
import com.research.assistant.dto.AnnotationRequest;
import com.research.assistant.entity.Paper;
import com.research.assistant.entity.PaperAnalysis;
import com.research.assistant.mapper.PaperAnalysisMapper;
import com.research.assistant.mapper.PaperMapper;
import com.research.assistant.service.LLMService;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 自动批注服务 —— 基于论文结构化分析结果生成分类批注。
 */
@Service
public class AiAnnotationService {

    private static final Logger log = LoggerFactory.getLogger(AiAnnotationService.class);

    private final PaperAnalysisMapper paperAnalysisMapper;
    private final PaperMapper paperMapper;
    private final AnnotationService annotationService;
    private final LLMService llmService;
    private final ObjectMapper objectMapper;

    @Value("${app.storage.pdf-dir:./data/papers}")
    private String pdfStorageDir;

    public AiAnnotationService(PaperAnalysisMapper paperAnalysisMapper,
                               PaperMapper paperMapper,
                               AnnotationService annotationService,
                               LLMService llmService,
                               ObjectMapper objectMapper) {
        this.paperAnalysisMapper = paperAnalysisMapper;
        this.paperMapper = paperMapper;
        this.annotationService = annotationService;
        this.llmService = llmService;
        this.objectMapper = objectMapper;
    }

    /**
     * 为指定论文生成 AI 自动批注，并持久化到数据库。
     *
     * @return 已保存的批注列表
     */
    public List<AnnotationDto> generateAndSave(Long paperId) {
        PaperAnalysis analysis = paperAnalysisMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PaperAnalysis>()
                        .eq(PaperAnalysis::getPaperId, paperId));
        Paper paper = paperMapper.selectById(paperId);
        if (paper == null) {
            throw new IllegalArgumentException("论文不存在");
        }

        String prompt = analysis != null
                ? buildPrompt(analysis)
                : buildPromptFromPdf(paper);
        String response = llmService.chat("你是一位学术阅读助手。请只返回 JSON 数组，不要 markdown 代码块。", prompt);
        List<AiAnnotationCandidate> candidates = parseCandidates(response);

        List<AnnotationDto> saved = new ArrayList<>();
        for (AiAnnotationCandidate c : candidates) {
            AnchorLocation location = locateAnchor(paper, c.anchorText(), saved.size());
            AnnotationRequest request = new AnnotationRequest();
            request.setType(location.anchored() ? mapType(c.category()) : "NOTE");
            request.setPage(location.page());
            request.setColor(mapColor(c.category()));
            request.setNote(c.note());
            request.setCoordinates(location.coordinates());
            saved.add(annotationService.create(paperId, request, true));
        }
        return saved;
    }

    private String buildPrompt(PaperAnalysis analysis) {
        StringBuilder sb = new StringBuilder();
        sb.append("请根据以下论文分析结果，生成 3-8 条自动批注，帮助读者快速定位重点。\n\n");
        sb.append("可选分类：METHOD（核心方法）、INNOVATION（创新点）、EXPERIMENT（实验结论）、ISSUE（潜在问题）。\n\n");
        sb.append("输出 JSON 数组，每个元素格式：\n");
        sb.append("{\"category\":\"METHOD\",\"anchorText\":\"文中与批注相关的原句片段\",\"note\":\"批注内容\"}\n\n");
        sb.append("分析结果：\n");
        sb.append("核心贡献：").append(nullToEmpty(analysis.getCoreContribution())).append("\n");
        sb.append("方法概述：").append(nullToEmpty(analysis.getMethodSummary())).append("\n");
        sb.append("主要发现：").append(nullToEmpty(analysis.getKeyFindingsJson())).append("\n");
        sb.append("局限性：").append(nullToEmpty(analysis.getLimitationsJson())).append("\n");
        sb.append("实验设置：").append(nullToEmpty(analysis.getExperimentSetupJson())).append("\n");
        sb.append("Benchmark：").append(nullToEmpty(analysis.getBenchmarkResultsJson())).append("\n");
        return sb.toString();
    }

    /**
     * 没有执行过“论文精读分析”时，仍允许用户直接使用 PDF 页面上的 AI 批注。
     * 这里只取前 6 页并限制字符数，避免把整篇论文一次性塞进模型请求。
     */
    private String buildPromptFromPdf(Paper paper) {
        StringBuilder sb = new StringBuilder();
        sb.append("请根据以下论文信息和 PDF 正文片段，生成 3-8 条自动批注，帮助读者快速定位重点。\n\n");
        sb.append("可选分类：METHOD（核心方法）、INNOVATION（创新点）、EXPERIMENT（实验结论）、ISSUE（潜在问题）。\n\n");
        sb.append("输出 JSON 数组，每个元素格式：\n");
        sb.append("{\"category\":\"METHOD\",\"anchorText\":\"文中与批注相关的原句片段\",\"note\":\"批注内容\"}\n\n");
        sb.append("论文标题：").append(nullToEmpty(paper.getTitle())).append("\n");
        sb.append("摘要：").append(nullToEmpty(paper.getAbstractText())).append("\n");
        sb.append("PDF 正文片段：\n").append(extractPdfText(paper)).append("\n");
        return sb.toString();
    }

    private String extractPdfText(Paper paper) {
        File file = resolveFile(paper.getPdfPath());
        if (file == null || !file.exists()) {
            return "（未找到 PDF 文件，请仅根据标题和摘要生成批注）";
        }
        try (PDDocument doc = Loader.loadPDF(file)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(1);
            stripper.setEndPage(Math.min(doc.getNumberOfPages(), 6));
            String text = stripper.getText(doc);
            if (text == null || text.isBlank()) {
                return "（PDF 没有可提取文本，请仅根据标题和摘要生成批注）";
            }
            return text.length() > 12000 ? text.substring(0, 12000) : text;
        } catch (Exception e) {
            log.warn("AI 批注读取 PDF 文本失败: {}", e.getMessage());
            return "（PDF 文本读取失败，请仅根据标题和摘要生成批注）";
        }
    }

    private List<AiAnnotationCandidate> parseCandidates(String response) {
        if (response == null || response.isBlank()) {
            return List.of();
        }
        try {
            String json = com.research.assistant.common.JsonUtils.extractJson(response);
            if (json == null || json.isBlank()) {
                return List.of();
            }
            return objectMapper.readValue(json, new TypeReference<List<AiAnnotationCandidate>>() {});
        } catch (Exception e) {
            log.warn("AI 自动批注 JSON 解析失败: {}", e.getMessage());
            return List.of();
        }
    }

    private String mapType(String category) {
        return switch (category == null ? "" : category.toUpperCase()) {
            case "METHOD" -> "HIGHLIGHT";
            case "INNOVATION" -> "HIGHLIGHT";
            case "EXPERIMENT" -> "UNDERLINE";
            case "ISSUE" -> "NOTE";
            default -> "NOTE";
        };
    }

    private String mapColor(String category) {
        return switch (category == null ? "" : category.toUpperCase()) {
            case "METHOD" -> "#2196f3";
            case "INNOVATION" -> "#ffeb3b";
            case "EXPERIMENT" -> "#4caf50";
            case "ISSUE" -> "#f44336";
            default -> "#ffeb3b";
        };
    }

    private AnchorLocation locateAnchor(Paper paper, String anchorText, int ordinal) {
        File file = resolveFile(paper.getPdfPath());
        if (file == null || !file.exists()) {
            return new AnchorLocation(1, fallbackCoordinates(ordinal), false);
        }
        try (PDDocument doc = Loader.loadPDF(file)) {
            for (int i = 1; i <= doc.getNumberOfPages(); i++) {
                AnchorTextStripper stripper = new AnchorTextStripper();
                stripper.setSortByPosition(true);
                stripper.setStartPage(i);
                stripper.setEndPage(i);
                stripper.getText(doc);
                NormalizedText pageText = stripper.normalizedText();
                String needle = normalizeAnchor(anchorText);
                int start = needle.isBlank() ? -1 : pageText.value().indexOf(needle);
                if (start < 0) {
                    String token = firstMeaningfulToken(anchorText);
                    start = token.isBlank() ? -1 : pageText.value().indexOf(token);
                    if (start >= 0) {
                        needle = token;
                    }
                }
                if (start >= 0 && start < pageText.positions().size()) {
                    int end = Math.min(pageText.positions().size(), start + Math.max(1, needle.length()));
                    List<Map<String, Object>> quads = buildQuads(doc.getPage(i - 1), pageText.positions().subList(start, end));
                    if (!quads.isEmpty()) {
                        Map<String, Object> coordinates = new LinkedHashMap<>();
                        coordinates.put("pageWidth", doc.getPage(i - 1).getMediaBox().getWidth());
                        coordinates.put("pageHeight", doc.getPage(i - 1).getMediaBox().getHeight());
                        coordinates.put("rotation", doc.getPage(i - 1).getRotation());
                        coordinates.put("scale", 1.5);
                        coordinates.put("quads", quads);
                        return new AnchorLocation(i, coordinates, true);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("定位 anchor 页码失败: {}", e.getMessage());
        }
        return new AnchorLocation(1, fallbackCoordinates(ordinal), false);
    }

    private List<Map<String, Object>> buildQuads(PDPage page, List<TextPosition> positions) {
        if (positions == null || positions.isEmpty()) return List.of();
        double pageWidth = page.getMediaBox().getWidth();
        double pageHeight = page.getMediaBox().getHeight();
        List<Map<String, Object>> quads = new ArrayList<>();
        double minX = Double.MAX_VALUE;
        double maxX = 0;
        double minY = Double.MAX_VALUE;
        double maxY = 0;
        double lineY = Double.NaN;

        for (TextPosition position : positions) {
            double x = position.getXDirAdj();
            double y = position.getYDirAdj();
            double right = x + Math.max(1, position.getWidthDirAdj());
            double bottom = y + Math.max(1, position.getHeightDir());
            double tolerance = Math.max(2, position.getHeightDir() * 0.7);
            if (!Double.isNaN(lineY) && Math.abs(y - lineY) > tolerance) {
                addQuad(quads, minX, maxX, minY, maxY, pageWidth, pageHeight);
                minX = Double.MAX_VALUE;
                maxX = 0;
                minY = Double.MAX_VALUE;
                maxY = 0;
            }
            lineY = y;
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, right);
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, bottom);
        }
        addQuad(quads, minX, maxX, minY, maxY, pageWidth, pageHeight);
        return quads;
    }

    private void addQuad(List<Map<String, Object>> quads,
                         double minX, double maxX, double minY, double maxY,
                         double pageWidth, double pageHeight) {
        if (minX == Double.MAX_VALUE || pageWidth <= 0 || pageHeight <= 0) return;
        double x1 = clamp(minX / pageWidth);
        double x2 = clamp(maxX / pageWidth);
        double y1 = clamp((pageHeight - minY) / pageHeight);
        double y3 = clamp((pageHeight - maxY) / pageHeight);
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("x1", x1);
        q.put("y1", y1);
        q.put("x2", x2);
        q.put("y2", y1);
        q.put("x3", x2);
        q.put("y3", y3);
        q.put("x4", x1);
        q.put("y4", y3);
        quads.add(q);
    }

    private Map<String, Object> fallbackCoordinates(int ordinal) {
        double y = 0.95 - (ordinal % 8) * 0.055;
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("x1", 0.93);
        q.put("y1", y);
        q.put("x2", 0.97);
        q.put("y2", y);
        q.put("x3", 0.97);
        q.put("y3", y - 0.025);
        q.put("x4", 0.93);
        q.put("y4", y - 0.025);
        Map<String, Object> coordinates = new LinkedHashMap<>();
        coordinates.put("pageWidth", 612);
        coordinates.put("pageHeight", 792);
        coordinates.put("rotation", 0);
        coordinates.put("scale", 1.5);
        coordinates.put("quads", List.of(q));
        return coordinates;
    }

    private double clamp(double value) {
        return Math.max(0, Math.min(1, value));
    }

    private String normalizeAnchor(String value) {
        if (value == null) return "";
        StringBuilder normalized = new StringBuilder();
        value.codePoints().forEach(codePoint -> {
            if (Character.isLetterOrDigit(codePoint)) {
                normalized.appendCodePoint(Character.toLowerCase(codePoint));
            }
        });
        return normalized.toString();
    }

    private String firstMeaningfulToken(String value) {
        if (value == null) return "";
        for (String token : value.split("\\s+")) {
            String normalized = normalizeAnchor(token);
            if (normalized.length() >= 4) return normalized;
        }
        return normalizeAnchor(value);
    }

    private File resolveFile(String pdfPath) {
        if (pdfPath == null || pdfPath.isBlank()) {
            return null;
        }
        File dir = new File(pdfStorageDir);
        if (!dir.isAbsolute()) {
            dir = new File(System.getProperty("user.dir"), pdfStorageDir);
        }
        File file = new File(dir, pdfPath);
        return file.exists() ? file : null;
    }

    private String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private record AnchorLocation(int page, Map<String, Object> coordinates, boolean anchored) {
    }

    private record NormalizedText(String value, List<TextPosition> positions) {
    }

    private static final class AnchorTextStripper extends PDFTextStripper {
        private final List<TextPosition> positions = new ArrayList<>();

        private AnchorTextStripper() throws IOException {
            super();
        }

        @Override
        protected void writeString(String text, List<TextPosition> textPositions) throws IOException {
            if (textPositions != null) positions.addAll(textPositions);
            super.writeString(text, textPositions);
        }

        private NormalizedText normalizedText() {
            StringBuilder value = new StringBuilder();
            List<TextPosition> mapped = new ArrayList<>();
            for (TextPosition position : positions) {
                String unicode = position.getUnicode();
                if (unicode == null) continue;
                unicode.codePoints().forEach(codePoint -> {
                    if (Character.isLetterOrDigit(codePoint)) {
                        value.appendCodePoint(Character.toLowerCase(codePoint));
                        mapped.add(position);
                    }
                });
            }
            return new NormalizedText(value.toString(), mapped);
        }
    }

    private record AiAnnotationCandidate(String category, String anchorText, String note) {
    }
}
