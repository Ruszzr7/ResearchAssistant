package com.research.assistant.service.writing;

import com.research.assistant.dto.NoteDto;
import com.research.assistant.dto.NoteRequest;
import com.research.assistant.dto.WritingProjectDto;
import com.research.assistant.dto.WritingProjectRequest;
import com.research.assistant.entity.Paper;
import com.research.assistant.entity.WritingProjectPaper;
import com.research.assistant.mapper.PaperMapper;
import com.research.assistant.mapper.WritingProjectMapper;
import com.research.assistant.mapper.WritingProjectPaperMapper;
import com.research.assistant.service.note.NoteService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
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
    private NoteService noteService;

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

        NoteRequest noteReq = new NoteRequest();
        noteReq.setTitle("关键发现");
        noteReq.setContent("这是笔记内容");
        noteService.create(paper.getId(), noteReq);

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

    private WritingProjectRequest projectReq(String title) {
        WritingProjectRequest r = new WritingProjectRequest();
        r.setTitle(title);
        return r;
    }
}
