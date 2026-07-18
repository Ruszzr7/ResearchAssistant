package com.research.assistant.service.writing;

import com.research.assistant.dto.WritingClaimRequest;
import com.research.assistant.dto.WritingEvidenceRequest;
import com.research.assistant.dto.WritingProjectDto;
import com.research.assistant.dto.WritingProjectRequest;
import com.research.assistant.entity.Paper;
import com.research.assistant.entity.ResearchSession;
import com.research.assistant.mapper.PaperMapper;
import com.research.assistant.mapper.ResearchSessionMapper;
import com.research.assistant.mapper.ResearchSessionPaperMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
@Rollback
class WritingEvidenceServiceTest {

    @Autowired
    private WritingEvidenceService service;
    @Autowired
    private WritingProjectService projectService;
    @Autowired
    private PaperMapper paperMapper;
    @Autowired
    private ResearchSessionMapper sessionMapper;
    @Autowired
    private ResearchSessionPaperMapper sessionPaperMapper;

    @Test
    void claimStateShouldFollowAttachedEvidence() {
        Fixture fixture = fixture();
        var claim = service.createClaim(fixture.project().getId(), claimRequest("方法", "方法优于基线"));
        assertThat(claim.getEvidenceState()).isEqualTo("NEEDS_EVIDENCE");

        var support = service.addEvidence(claim.getId(), evidenceRequest(
                fixture.paper().getId(), "supports", "The method improves sum rate."));
        assertThat(support.getPaperTitle()).isEqualTo("Evidence paper");

        var supported = service.listClaims(fixture.project().getId()).get(0);
        assertThat(supported.getEvidenceState()).isEqualTo("SUPPORTED");
        assertThat(supported.getSupportCount()).isEqualTo(1);

        service.addEvidence(claim.getId(), evidenceRequest(
                fixture.paper().getId(), "CONTRADICTS", "The gain disappears at low SNR."));
        var mixed = service.listClaims(fixture.project().getId()).get(0);
        assertThat(mixed.getEvidenceState()).isEqualTo("MIXED");
        assertThat(mixed.getContradictionCount()).isEqualTo(1);

        service.deleteEvidence(support.getId());
        assertThat(service.listClaims(fixture.project().getId()).get(0).getEvidenceState())
                .isEqualTo("CONFLICTING");
    }

    @Test
    void evidencePaperMustBelongToWritingProject() {
        Fixture fixture = fixture();
        Paper outsider = paper("Outsider");
        var claim = service.createClaim(fixture.project().getId(), claimRequest("引言", "claim"));

        assertThatThrownBy(() -> service.addEvidence(claim.getId(),
                evidenceRequest(outsider.getId(), "SUPPORTS", "quote")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("尚未加入");
    }

    @Test
    void linkedResearchSessionMustContainEvidencePaper() {
        Fixture fixture = fixture();
        Paper other = paper("Other paper");
        ResearchSession session = new ResearchSession();
        session.setSessionKey("writing-evidence-test");
        session.setTitle("Other session");
        session.setPrimaryPaperId(other.getId());
        sessionMapper.insert(session);
        sessionPaperMapper.insertLink(session.getId(), other.getId(), 0);

        var claim = service.createClaim(fixture.project().getId(), claimRequest("实验", "claim"));
        WritingEvidenceRequest request = evidenceRequest(
                fixture.paper().getId(), "SUPPORTS", "quote");
        request.setResearchSessionId(session.getId());

        assertThatThrownBy(() -> service.addEvidence(claim.getId(), request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不包含该论文");
    }

    private Fixture fixture() {
        Paper paper = paper("Evidence paper");
        WritingProjectRequest request = new WritingProjectRequest();
        request.setTitle("Evidence project");
        request.setTopic("Traceable writing");
        WritingProjectDto project = projectService.createProject(request);
        projectService.addPaper(project.getId(), paper.getId());
        return new Fixture(project, paper);
    }

    private Paper paper(String title) {
        Paper paper = new Paper();
        paper.setTitle(title);
        paperMapper.insert(paper);
        return paper;
    }

    private WritingClaimRequest claimRequest(String section, String claim) {
        WritingClaimRequest request = new WritingClaimRequest();
        request.setSectionName(section);
        request.setClaimText(claim);
        return request;
    }

    private WritingEvidenceRequest evidenceRequest(Long paperId, String relation, String quote) {
        WritingEvidenceRequest request = new WritingEvidenceRequest();
        request.setPaperId(paperId);
        request.setRelationType(relation);
        request.setQuoteText(quote);
        request.setPageNumber(2);
        return request;
    }

    private record Fixture(WritingProjectDto project, Paper paper) {
    }
}
