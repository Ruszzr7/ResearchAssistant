package com.research.assistant.service.workbench;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.entity.PaperAnalysis;
import com.research.assistant.mapper.PaperAnalysisMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/** Persists only gate-approved whole-paper reports and their evidence/version identity. */
@Service
public class WorkbenchAnalysisReportService {

    private final PaperAnalysisMapper analysisMapper;
    private final ObjectMapper objectMapper;

    public WorkbenchAnalysisReportService(PaperAnalysisMapper analysisMapper, ObjectMapper objectMapper) {
        this.analysisMapper = analysisMapper;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void persist(Long paperId,
                        WorkbenchWorkflowResult result,
                        WorkbenchPlan.ArtifactVersion artifactVersion,
                        int tokenUsage) {
        if (paperId == null || result == null || artifactVersion == null
                || result.workflow() != WorkbenchPlan.Workflow.PAPER_ANALYSIS) {
            throw new IllegalArgumentException("invalid grounded analysis report");
        }
        PaperAnalysis analysis = analysisMapper.selectOne(new LambdaQueryWrapper<PaperAnalysis>()
                .eq(PaperAnalysis::getPaperId, paperId));
        boolean create = analysis == null;
        if (create) {
            analysis = new PaperAnalysis();
            analysis.setPaperId(paperId);
            analysis.setCreatedAt(LocalDateTime.now());
        }
        analysis.setGroundedReport(result.answer());
        analysis.setGroundedEvidenceIdsJson(write(result.claims().stream()
                .flatMap(claim -> claim.evidenceIds().stream()).distinct().toList()));
        analysis.setWorkbenchRunId(result.runId());
        analysis.setLayoutDocumentHash(artifactVersion.documentHash());
        analysis.setLayoutParserVersion(artifactVersion.parserVersion());
        analysis.setTokenUsed(Math.max(0, tokenUsage));
        if (create) analysisMapper.insert(analysis); else analysisMapper.updateById(analysis);
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("grounded report evidence cannot be serialized", e);
        }
    }
}
