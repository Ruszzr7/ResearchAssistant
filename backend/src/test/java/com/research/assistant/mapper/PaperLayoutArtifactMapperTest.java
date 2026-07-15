package com.research.assistant.mapper;

import com.research.assistant.entity.PaperLayoutArtifactRecord;
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
class PaperLayoutArtifactMapperTest {

    @Autowired private PaperLayoutArtifactMapper mapper;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void shouldPersistAndResolveMatchingVersionedArtifact() {
        jdbcTemplate.update("INSERT INTO paper (id, title) VALUES (?, ?)", 91001L, "Layout paper");
        PaperLayoutArtifactRecord record = new PaperLayoutArtifactRecord();
        record.setPaperId(91001L);
        record.setDocumentHash("b".repeat(64));
        record.setParserVersion("pdfbox-layout-v1+semantic-v1");
        record.setStatus("READY");
        record.setLayoutConfidence(0.88);
        record.setPageCount(12);
        record.setBlocksJson("[]");
        record.setGeneratedAt(LocalDateTime.now());
        record.setCreatedAt(LocalDateTime.now());
        record.setUpdatedAt(LocalDateTime.now());

        assertThat(mapper.insert(record)).isEqualTo(1);
        PaperLayoutArtifactRecord selected = mapper.selectReady(
                91001L, "b".repeat(64), "pdfbox-layout-v1+semantic-v1");

        assertThat(selected).isNotNull();
        assertThat(selected.getId()).isEqualTo(record.getId());
        assertThat(selected.getLayoutConfidence()).isEqualTo(0.88);
        assertThat(mapper.selectLatestReady(91001L).getPageCount()).isEqualTo(12);
    }
}
