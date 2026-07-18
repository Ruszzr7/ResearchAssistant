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
        assertThat(mapper.selectLatest(92001L).getDocumentHash()).isEqualTo("c".repeat(64));
    }
}
