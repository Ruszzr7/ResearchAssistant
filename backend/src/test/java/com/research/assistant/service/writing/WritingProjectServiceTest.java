package com.research.assistant.service.writing;

import com.research.assistant.dto.AnnotationRequest;
import com.research.assistant.dto.NoteDto;
import com.research.assistant.dto.WritingProjectDto;
import com.research.assistant.dto.WritingProjectRequest;
import com.research.assistant.dto.WritingClaimRequest;
import com.research.assistant.dto.WritingEvidenceRequest;
import com.research.assistant.entity.Paper;
import com.research.assistant.entity.WritingProjectPaper;
import com.research.assistant.mapper.PaperMapper;
import com.research.assistant.mapper.WritingProjectMapper;
import com.research.assistant.mapper.WritingProjectPaperMapper;
import com.research.assistant.service.annotation.AnnotationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
@Rollback
class WritingProjectServiceTest {

    @Autowired
    private WritingProjectService service;

    @Autowired
    private WritingProjectMapper projectMapper;

    @Autowired
    private WritingProjectPaperMapper linkMapper;

    @Autowired
    private PaperMapper paperMapper;

    @Autowired
    private AnnotationService annotationService;

    @Autowired
    private WritingEvidenceService evidenceService;

    @Test
    void createAndGetProject() {
        WritingProjectRequest request = new WritingProjectRequest();
        request.setTitle("毕业论文");
        request.setTopic("多模态医疗影像");

        WritingProjectDto created = service.createProject(request);
        assertThat(created.getTitle()).isEqualTo("毕业论文");
        assertThat(created.getTopic()).isEqualTo("多模态医疗影像");

        WritingProjectDto fetched = service.getProject(created.getId());
        assertThat(fetched.getTitle()).isEqualTo("毕业论文");
        assertThat(fetched.getPaperIds()).isEmpty();
    }

    @Test
    void addPaperAndListNotes() {
        Paper paper = new Paper();
        paper.setTitle("Note Source");
        paperMapper.insert(paper);

        AnnotationRequest noteReq = new AnnotationRequest();
        noteReq.setType("NOTE");
        noteReq.setPage(2);
        noteReq.setColor("#f44336");
        noteReq.setNote("这是笔记内容");
        noteReq.setCoordinates(Map.of(
                "anchorKind", "SELECTION",
                "anchorText", "关键发现",
                "anchorQuads", List.of(Map.of(
                        "x1", 0.1, "y1", 0.2, "x2", 0.3, "y2", 0.2,
                        "x3", 0.3, "y3", 0.16, "x4", 0.1, "y4", 0.16))));
        annotationService.create(paper.getId(), noteReq);

        WritingProjectDto project = service.createProject(projectReq("proj"));
        service.addPaper(project.getId(), paper.getId());

        WritingProjectDto fetched = service.getProject(project.getId());
        assertThat(fetched.getPaperIds()).containsExactly(paper.getId());

        List<NoteDto> notes = service.listNotesForProject(project.getId());
        assertThat(notes).hasSize(1);
        assertThat(notes.get(0).getContent()).isEqualTo("这是笔记内容");
    }

    @Test
    void removePaperFromProject() {
        Paper paper = new Paper();
        paper.setTitle("x");
        paperMapper.insert(paper);

        WritingProjectDto project = service.createProject(projectReq("proj"));
        service.addPaper(project.getId(), paper.getId());
        service.removePaper(project.getId(), paper.getId());

        assertThat(service.getProject(project.getId()).getPaperIds()).isEmpty();
    }

    @Test
    void deleteProjectCascadesLinks() {
        Paper paper = new Paper();
        paper.setTitle("x");
        paperMapper.insert(paper);

        WritingProjectDto project = service.createProject(projectReq("proj"));
        service.addPaper(project.getId(), paper.getId());
        service.deleteProject(project.getId());

        assertThat(service.getProject(project.getId())).isNull();
        assertThat(linkMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<WritingProjectPaper>()
                        .eq(WritingProjectPaper::getProjectId, project.getId()))).isEmpty();
    }

    @Test
    void duplicatePaperRejected() {
        Paper paper = new Paper();
        paper.setTitle("x");
        paperMapper.insert(paper);

        WritingProjectDto project = service.createProject(projectReq("proj"));
        service.addPaper(project.getId(), paper.getId());

        assertThatThrownBy(() -> service.addPaper(project.getId(), paper.getId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("已加入");
    }

    @Test
    void generatedContentShouldBePersisted() {
        WritingProjectDto project = service.createProject(projectReq("proj"));
        WritingProjectRequest update = projectReq("proj");
        update.setOutlineJson("{\"sections\":[]}");
        update.setRelatedWork("durable related work");

        WritingProjectDto saved = service.updateProject(project.getId(), update);

        assertThat(saved.getOutlineJson()).isEqualTo("{\"sections\":[]}");
        assertThat(saved.getRelatedWork()).isEqualTo("durable related work");
    }

    @Test
    void paperReferencedByClaimEvidenceCannotBeRemoved() {
        Paper paper = new Paper();
        paper.setTitle("evidence source");
        paperMapper.insert(paper);
        WritingProjectDto project = service.createProject(projectReq("proj"));
        service.addPaper(project.getId(), paper.getId());

        WritingClaimRequest claimRequest = new WritingClaimRequest();
        claimRequest.setSectionName("引言");
        claimRequest.setClaimText("A supported claim");
        var claim = evidenceService.createClaim(project.getId(), claimRequest);

        WritingEvidenceRequest evidenceRequest = new WritingEvidenceRequest();
        evidenceRequest.setPaperId(paper.getId());
        evidenceRequest.setRelationType("SUPPORTS");
        evidenceRequest.setQuoteText("Evidence from the paper.");
        evidenceService.addEvidence(claim.getId(), evidenceRequest);

        assertThatThrownBy(() -> service.removePaper(project.getId(), paper.getId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("仍被论点证据引用");
    }

    private WritingProjectRequest projectReq(String title) {
        WritingProjectRequest r = new WritingProjectRequest();
        r.setTitle(title);
        return r;
    }
}
