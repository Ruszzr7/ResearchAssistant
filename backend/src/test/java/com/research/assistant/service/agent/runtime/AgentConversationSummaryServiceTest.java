package com.research.assistant.service.agent.runtime;

import com.research.assistant.entity.AgentConversationSummaryRecord;
import com.research.assistant.entity.ResearchMessage;
import com.research.assistant.mapper.AgentConversationSummaryMapper;
import com.research.assistant.mapper.ResearchMessageMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AgentConversationSummaryServiceTest {

    @Autowired private AgentConversationSummaryService service;
    @Autowired private AgentConversationSummaryMapper summaryMapper;
    @Autowired private ResearchMessageMapper messageMapper;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void storesVersionedRebuildableSummaryInsideOneSession() {
        insertSession(94001L, "summary-session-1");
        insertSession(94002L, "summary-session-2");
        ResearchMessage message = new ResearchMessage();
        message.setSessionId(94001L);
        message.setMessageKey("message-1");
        message.setRole("USER");
        message.setMessageType("CHAT");
        message.setMessageStatus("FINAL");
        message.setContent("question");
        messageMapper.insert(message);

        AgentConversationSummaryRecord first = service.save(
                94001L, message.getId(), "agent-summary-v1", "{\"topic\":\"one\"}");
        AgentConversationSummaryRecord second = service.save(
                94001L, message.getId(), "agent-summary-v1", "{\"topic\":\"two\"}");

        assertThat(first.getRevision()).isEqualTo(1);
        assertThat(second.getRevision()).isEqualTo(2);
        assertThat(summaryMapper.selectLatest(94001L).getSummaryJson()).contains("two");
        assertThatThrownBy(() -> service.save(
                94002L, message.getId(), "agent-summary-v1", "{}"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void compactsOldMessagesAndKeepsRecentConversationVerbatim() {
        insertSession(94003L, "summary-session-3");
        for (int index = 1; index <= 26; index++) {
            ResearchMessage message = new ResearchMessage();
            message.setSessionId(94003L);
            message.setMessageKey("compact-message-" + index);
            message.setRole(index % 2 == 0 ? "ASSISTANT" : "USER");
            message.setMessageType("CHAT");
            message.setMessageStatus("FINAL");
            message.setContent("message content " + index);
            messageMapper.insert(message);
        }

        AgentConversationSummaryRecord summary = service.compactIfNeeded(94003L);

        assertThat(summary).isNotNull();
        assertThat(summary.getSchemaVersion()).isEqualTo(AgentConversationSummaryService.SCHEMA_VERSION);
        assertThat(summary.getSummaryJson()).contains("message content 1", "message content 14");
        assertThat(messageMapper.selectFinalAfter(94003L, summary.getCoveredThroughMessageId()))
                .hasSize(12)
                .extracting(ResearchMessage::getContent)
                .containsExactly("message content 15", "message content 16", "message content 17",
                        "message content 18", "message content 19", "message content 20",
                        "message content 21", "message content 22", "message content 23",
                        "message content 24", "message content 25", "message content 26");
    }

    private void insertSession(long id, String key) {
        jdbcTemplate.update("INSERT INTO research_session "
                        + "(id, session_key, title, session_type, mode, output_language, archived) "
                        + "VALUES (?, ?, ?, 'SINGLE', 'analysis', 'ZH', FALSE)",
                id, key, key);
    }
}
