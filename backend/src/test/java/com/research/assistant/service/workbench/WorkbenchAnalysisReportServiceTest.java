package com.research.assistant.service.workbench;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.research.assistant.entity.Paper;
import com.research.assistant.entity.PaperAnalysis;
import com.research.assistant.mapper.PaperAnalysisMapper;
import com.research.assistant.mapper.PaperMapper;
import com.research.assistant.service.pdf.layout.DocumentBlockRole;
import com.research.assistant.service.pdf.layout.LayoutEvidence;
import com.research.assistant.service.pdf.layout.NormalizedBoundingBox;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class WorkbenchAnalysisReportServiceTest {

    @Autowired private WorkbenchAnalysisReportService service;
    @Autowired private PaperAnalysisMapper analysisMapper;
    @Autowired private PaperMapper paperMapper;

    @Test
    void createsAndUpdatesOneVersionBoundGroundedReport() {
        Paper paper = new Paper();
        paper.setTitle("Grounded paper");
        paperMapper.insert(paper);
        WorkbenchPlan.ArtifactVersion version = new WorkbenchPlan.ArtifactVersion(
                paper.getId(), "a".repeat(64), "parser-v1", 0.91);

        WorkbenchWorkflowResult first = result(paper.getId(), "run-first", "First grounded report");
        service.persist(paper.getId(), first, version, 123);

        PaperAnalysis persisted = find(paper.getId());
        assertThat(persisted.getGroundedReport()).isEqualTo("First grounded report");
        assertThat(persisted.getGroundedEvidenceIdsJson()).isEqualTo("[\"lay_a\"]");
        assertThat(persisted.getWorkbenchRunId()).isEqualTo("run-first");
        assertThat(persisted.getLayoutDocumentHash()).isEqualTo("a".repeat(64));
        assertThat(persisted.getLayoutParserVersion()).isEqualTo("parser-v1");
        assertThat(persisted.getTokenUsed()).isEqualTo(123);

        WorkbenchWorkflowResult second = result(paper.getId(), "run-second", "Updated grounded report");
        service.persist(paper.getId(), second, version, 77);

        assertThat(find(paper.getId()).getGroundedReport()).isEqualTo("Updated grounded report");
        assertThat(find(paper.getId()).getWorkbenchRunId()).isEqualTo("run-second");
        assertThat(analysisMapper.selectCount(new LambdaQueryWrapper<PaperAnalysis>()
                .eq(PaperAnalysis::getPaperId, paper.getId()))).isEqualTo(1);
    }

    private PaperAnalysis find(Long paperId) {
        return analysisMapper.selectOne(new LambdaQueryWrapper<PaperAnalysis>()
                .eq(PaperAnalysis::getPaperId, paperId));
    }

    private WorkbenchWorkflowResult result(Long paperId, String runId, String answer) {
        LayoutEvidence evidence = new LayoutEvidence("lay_a", paperId, "p1-b0001", 1,
                new NormalizedBoundingBox(0.1, 0.2, 0.3, 0.04), DocumentBlockRole.BODY,
                1, List.of("Introduction"), "Evidence", 0.9, false, 0.95,
                "a".repeat(64), "parser-v1");
        return new WorkbenchWorkflowResult(runId, WorkbenchPlan.Workflow.PAPER_ANALYSIS,
                WorkbenchPlan.Scope.PAPER, List.of(paperId), answer,
                List.of(new WorkbenchEvidenceGate.GroundedClaim("claim", List.of("lay_a"))),
                List.of(evidence), null, false, 0);
    }
}
