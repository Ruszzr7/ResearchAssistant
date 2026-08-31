package com.research.assistant.service.research;

import com.research.assistant.dto.research.ResearchSessionCreateRequest;
import com.research.assistant.dto.research.ResearchSessionDetail;
import com.research.assistant.dto.research.ResearchSessionUpdateRequest;
import com.research.assistant.entity.Paper;
import com.research.assistant.mapper.PaperMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
@Rollback
class ResearchSessionServiceTest {

    @Autowired private ResearchSessionService service;
    @Autowired private PaperMapper paperMapper;

    @Test
    void persistsSessionPapersMessagesAndResumeState() {
        Paper paper = new Paper();
        paper.setTitle("Durable Research Session");
        paper.setYear(2026);
        paperMapper.insert(paper);

        var created = service.create(new ResearchSessionCreateRequest(
                List.of(paper.getId()), paper.getId(), null, "PAPER_ANALYSIS", 3, "ZH"));
        assertThat(created.getTitle()).isEqualTo("Durable Research Session");
        assertThat(created.getPapers()).extracting("id").containsExactly(paper.getId());

        service.update(created.getId(), new ResearchSessionUpdateRequest(
                "RSMA 会话", 13, "selection_qa", "EN", null, null));
        ResearchSessionDetail detail = service.get(created.getId());

        assertThat(detail.session().getTitle()).isEqualTo("RSMA 会话");
        assertThat(detail.session().getLastPage()).isEqualTo(13);
        assertThat(detail.session().getOutputLanguage()).isEqualTo("EN");
        assertThat(detail.messages()).isEmpty();
    }
}
