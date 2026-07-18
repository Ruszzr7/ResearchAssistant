package com.research.assistant.mapper;

import com.research.assistant.entity.PaperMemoryRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PaperMemoryMapperTest {

    @Autowired private PaperMemoryMapper mapper;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void shouldPersistAndResolveExactStructuredMemoryVersion() {
        jdbcTemplate.update("INSERT INTO paper (id, title) VALUES (?, ?)",
                92001L, "Memory paper");
        PaperMemoryRecord record = new PaperMemoryRecord();
        record.setPaperId(92001L);
        record.setDocumentHash("c".repeat(64));
        record.setLayoutParserVersion("pdfbox-layout-v1+semantic-v2");
        record.setSchemaVersion("paper-structure-v1");
        record.setStatus("STRUCTURED");
        record.setStructureJson("{\"schemaVersion\":\"paper-structure-v1\"}");
        record.setMemoryQualityJson("{\"layoutConfidence\":0.9}");
        record.setUnderstandingVersion("paper-understanding-v1");
        record.setStageText("论文记忆已就绪");
        record.setTotalChunks(2);
        record.setCompletedChunks(2);
        record.setFailedChunks(0);
        record.setPromptTokens(30);
        record.setCompletionTokens(10);
        record.setChunkSummariesJson("[]");
        record.setProfileJson("{\"schemaVersion\":\"paper-profile-v1\"}");
        record.setRevision(1);
        record.setGeneratedAt(LocalDateTime.now());
        record.setCreatedAt(LocalDateTime.now());
        record.setUpdatedAt(LocalDateTime.now());

        assertThat(mapper.insert(record)).isEqualTo(1);
        PaperMemoryRecord selected = mapper.selectVersion(
                92001L,
                "c".repeat(64),
                "pdfbox-layout-v1+semantic-v2",
                "paper-structure-v1");

        assertThat(selected).isNotNull();
        assertThat(selected.getId()).isEqualTo(record.getId());
        assertThat(selected.getStatus()).isEqualTo("STRUCTURED");
        assertThat(selected.getStructureJson()).contains("paper-structure-v1");
        assertThat(selected.getUnderstandingVersion()).isEqualTo("paper-understanding-v1");
        assertThat(selected.getTotalChunks()).isEqualTo(2);
        assertThat(selected.getProfileJson()).contains("paper-profile-v1");
        assertThat(mapper.selectLatest(92001L).getDocumentHash()).isEqualTo("c".repeat(64));

        selected.setChunkSummariesJson(null);
        selected.setProfileJson(null);
        selected.setUnderstandingVersion(null);
        assertThat(mapper.updateById(selected)).isEqualTo(1);
        PaperMemoryRecord reset = mapper.selectLatest(92001L);
        assertThat(reset.getChunkSummariesJson()).isNull();
        assertThat(reset.getProfileJson()).isNull();
        assertThat(reset.getUnderstandingVersion()).isNull();
    }
}
