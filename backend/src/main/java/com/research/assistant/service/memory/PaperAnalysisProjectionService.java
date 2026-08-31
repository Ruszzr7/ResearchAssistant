package com.research.assistant.service.memory;

import com.research.assistant.entity.PaperAnalysis;
import com.research.assistant.mapper.PaperAnalysisMapper;
import com.research.assistant.service.pdf.layout.DocumentBlock;
import com.research.assistant.service.pdf.layout.DocumentBlockRole;
import com.research.assistant.service.pdf.layout.PaperLayoutArtifact;
import com.research.assistant.service.pdf.layout.PaperLayoutArtifactService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Projects the new paper memory into the legacy one-row analysis contract. */
@Service
public class PaperAnalysisProjectionService {

    private static final int LEGACY_RAW_TEXT_LIMIT = 12_000;

    private final PaperAnalysisMapper analysisMapper;
    private final PaperMemoryService memoryService;
    private final PaperLayoutArtifactService artifactService;
    private final ObjectMapper objectMapper;

    public PaperAnalysisProjectionService(PaperAnalysisMapper analysisMapper,
                                          PaperMemoryService memoryService,
                                          PaperLayoutArtifactService artifactService,
                                          ObjectMapper objectMapper) {
        this.analysisMapper = analysisMapper;
        this.memoryService = memoryService;
        this.artifactService = artifactService;
        this.objectMapper = objectMapper;
    }

    public PaperAnalysis project(Long paperId, PaperUnderstandingResult understanding) {
        if (understanding == null || !understanding.usable()) {
            throw new IllegalArgumentException("论文语义记忆尚不可用");
        }
        PaperMemoryState memory = memoryService.latestStructure(paperId);
        PaperLayoutArtifact artifact = artifactService.latestArtifact(paperId);
        if (memory == null || artifact == null) {
            throw new IllegalStateException("论文结构或版面制品不存在");
        }
        PaperGlobalProfile profile = understanding.profile();
        List<PaperChunkSummary> summaries = understanding.summaries().stream()
                .filter(PaperChunkSummary::ready).toList();
        PaperAnalysis analysis = analysisMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PaperAnalysis>()
                        .eq(PaperAnalysis::getPaperId, paperId));
        boolean insert = analysis == null;
        if (insert) {
            analysis = new PaperAnalysis();
            analysis.setPaperId(paperId);
            analysis.setCreatedAt(LocalDateTime.now());
        }

        List<PaperMemoryClaim> contributions = profile == null
                ? claims(summaries, "CONTRIBUTION") : profile.coreContributions();
        List<PaperMemoryClaim> findings = profile == null
                ? claims(summaries, "FINDING") : profile.keyFindings();
        List<PaperMemoryClaim> limitations = profile == null
                ? claims(summaries, "LIMITATION") : profile.limitations();
        String methodType = profile == null ? "OTHER" : profile.methodType();

        analysis.setCoreContribution(joinStatements(contributions));
        // `method_type` is a categorical legacy field.  Do not concatenate the
        // free-form profile domain here: that made perfectly valid whole-paper
        // profiles overflow the VARCHAR column (and also corrupted the meaning
        // of the field).  The domain remains available in the paper-memory
        // profile used by the agent.
        analysis.setMethodType(methodType);
        analysis.setMethodSummary(profile == null
                ? joinStatements(claims(summaries, "METHOD")) : profile.methodSummary());
        analysis.setSectionsJson(write(profile == null || profile.sectionDigests().isEmpty()
                ? memory.structure().sections() : profile.sectionDigests()));
        analysis.setDatasetsJson(write(profile == null
                ? collect(summaries, PaperChunkSummary::datasets) : profile.datasets()));
        analysis.setModelsJson(write(profile == null
                ? collect(summaries, PaperChunkSummary::models) : profile.models()));
        analysis.setKeyFindingsJson(write(statements(findings)));
        analysis.setLimitationsJson(write(statements(limitations)));
        analysis.setExperimentSetupJson(write(profile == null ? Map.of() : profile.experimentSetup()));
        analysis.setBenchmarkResultsJson(write(profile == null ? List.of() : profile.benchmarkResults()));
        analysis.setTablesSummaryJson(write(elements(memory.structure(), "TABLE")));
        analysis.setFiguresSummaryJson(write(elements(memory.structure(), "FIGURE", "CAPTION")));
        analysis.setReproducibleArtifactsJson(write(elements(memory.structure(), "FORMULA", "TABLE")));
        analysis.setFormulasJson(write(elements(memory.structure(), "FORMULA")));
        analysis.setFiguresJson(write(elements(memory.structure(), "FIGURE")));
        analysis.setRawText(legacyRawText(artifact));
        analysis.setLayoutDocumentHash(memory.documentHash());
        analysis.setLayoutParserVersion(memory.layoutParserVersion());
        analysis.setTokenUsed(understanding.promptTokens() + understanding.completionTokens());

        if (insert) analysisMapper.insert(analysis);
        else analysisMapper.updateById(analysis);
        return analysis;
    }

    private List<PaperMemoryClaim> claims(List<PaperChunkSummary> summaries, String category) {
        return summaries.stream().flatMap(summary -> summary.claims().stream())
                .filter(claim -> category.equals(claim.category()))
                .toList();
    }

    private String joinStatements(List<PaperMemoryClaim> claims) {
        return String.join("\n", statements(claims));
    }

    private List<String> statements(List<PaperMemoryClaim> claims) {
        return claims.stream().map(PaperMemoryClaim::statement).filter(value -> !value.isBlank())
                .distinct().toList();
    }

    private List<String> collect(List<PaperChunkSummary> summaries,
                                 java.util.function.Function<PaperChunkSummary, List<String>> source) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        summaries.forEach(summary -> result.addAll(source.apply(summary)));
        return List.copyOf(result);
    }

    private List<Map<String, Object>> elements(PaperStructure structure, String... types) {
        java.util.Set<String> allowed = java.util.Set.of(types);
        List<Map<String, Object>> result = new ArrayList<>();
        for (PaperStructure.Element element : structure.elements()) {
            if (!allowed.contains(element.type())) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", element.type());
            item.put("label", element.label());
            item.put("content", element.content());
            item.put("page", element.page());
            item.put("blockIds", element.blockIds());
            result.add(item);
        }
        return List.copyOf(result);
    }

    private String legacyRawText(PaperLayoutArtifact artifact) {
        StringBuilder text = new StringBuilder();
        for (DocumentBlock block : artifact.blocks().stream()
                .sorted(java.util.Comparator.comparingInt(DocumentBlock::readingOrder)).toList()) {
            if (skipLegacyText(block.role()) || block.text().isBlank()) continue;
            if (text.length() > 0) text.append("\n\n");
            int remaining = LEGACY_RAW_TEXT_LIMIT - text.length();
            if (remaining <= 0) break;
            text.append(block.text(), 0, Math.min(remaining, block.text().length()));
        }
        return text.toString();
    }

    private boolean skipLegacyText(DocumentBlockRole role) {
        return role == DocumentBlockRole.AUTHOR || role == DocumentBlockRole.HEADER
                || role == DocumentBlockRole.FOOTER || role == DocumentBlockRole.MARGIN_METADATA
                || role == DocumentBlockRole.REFERENCE;
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("兼容分析投影无法序列化", exception);
        }
    }
}
