package com.research.assistant.mapper;

import com.research.assistant.dto.AiQualityStatusCount;
import com.research.assistant.entity.AiQualityEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AiQualityEventMapperTest {

    @Autowired
    private AiQualityEventMapper mapper;

    @Test
    void shouldPersistAndSummarizeQualityEvent() {
        AiQualityEvent event = new AiQualityEvent();
        event.setRunId("run-1");
        event.setTaskType("PAPER_ANALYSIS");
        event.setStage("POJO");
        event.setPromptVersion("paper-analysis-v1");
        event.setStatus("PASS");
        event.setFinalStatus("PASS");
        event.setRepaired(false);
        event.setRetryCount(0);
        event.setValidationErrorsJson("[]");
        event.setPromptTokens(10);
        event.setCompletionTokens(20);
        event.setTotalTokens(30);
        event.setLatencyMs(120L);

        assertThat(mapper.insert(event)).isEqualTo(1);
        assertThat(event.getId()).isNotNull();

        List<AiQualityEvent> recent = mapper.selectRecent(5);
        assertThat(recent).extracting(AiQualityEvent::getPromptVersion)
                .contains("paper-analysis-v1");

        List<AiQualityStatusCount> summary = mapper.summarizeSince(LocalDateTime.now().minusDays(1));
        assertThat(summary).anyMatch(row -> "PASS".equals(row.getStatus())
                && row.getEventCount() >= 1 && row.getTotalTokens() >= 30);
    }
}
