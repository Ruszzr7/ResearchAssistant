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
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
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
        if (analysis == null) {
            throw new IllegalArgumentException("论文尚未完成 AI 分析，无法生成自动批注");
        }

        String prompt = buildPrompt(analysis);
        String response = llmService.chat("你是一位学术阅读助手。请只返回 JSON 数组，不要 markdown 代码块。", prompt);
        List<AiAnnotationCandidate> candidates = parseCandidates(response);

        List<AnnotationDto> saved = new ArrayList<>();
        for (AiAnnotationCandidate c : candidates) {
            int page = resolvePage(paperId, c.anchorText());
            AnnotationRequest request = new AnnotationRequest();
            request.setType(mapType(c.category()));
            request.setPage(page);
            request.setColor(mapColor(c.category()));
            request.setNote(c.note());
            request.setCoordinates(buildCoordinates(page));
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

    private Map<String, Object> buildCoordinates(int page) {
        Map<String, Object> coords = new LinkedHashMap<>();
        coords.put("page", page);
        coords.put("quads", List.of());
        return coords;
    }

    private int resolvePage(Long paperId, String anchorText) {
        if (anchorText == null || anchorText.isBlank()) {
            return 1;
        }
        Paper paper = paperMapper.selectById(paperId);
        if (paper == null || paper.getPdfPath() == null) {
            return 1;
        }
        File file = resolveFile(paper.getPdfPath());
        if (file == null || !file.exists()) {
            return 1;
        }
        try (PDDocument doc = Loader.loadPDF(file)) {
            PDFTextStripper stripper = new PDFTextStripper();
            for (int i = 1; i <= doc.getNumberOfPages(); i++) {
                stripper.setStartPage(i);
                stripper.setEndPage(i);
                String text = stripper.getText(doc);
                if (text != null && text.contains(anchorText)) {
                    return i;
                }
            }
        } catch (Exception e) {
            log.debug("定位 anchor 页码失败: {}", e.getMessage());
        }
        return 1;
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

    private record AiAnnotationCandidate(String category, String anchorText, String note) {
    }
}
