package com.research.assistant.service.agent.runtime;

import com.research.assistant.entity.AgentConversationSummaryRecord;
import com.research.assistant.entity.ResearchMessage;
import com.research.assistant.mapper.AgentConversationSummaryMapper;
import com.research.assistant.mapper.ResearchMessageMapper;
import com.research.assistant.service.ai.LangChain4jModelFactory;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.test.mock.mockito.MockBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:agent_summary_test;MODE=MySQL;"
        + "DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=VALUE,YEAR")
@ActiveProfiles("test")
class AgentConversationSummaryServiceTest {

    @Autowired private AgentConversationSummaryService service;
    @Autowired private AgentConversationSummaryMapper summaryMapper;
    @Autowired private ResearchMessageMapper messageMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @MockBean private LangChain4jModelFactory modelFactory;

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
    void asynchronouslySummarizesOldMessagesAndKeepsRecentConversationVerbatim() throws Exception {
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

        ChatModel model = mock(ChatModel.class);
        when(modelFactory.createPaperUnderstandingModel()).thenReturn(model);
        when(model.chat(any(dev.langchain4j.model.chat.request.ChatRequest.class))).thenReturn(
                ChatResponse.builder().aiMessage(AiMessage.from("""
                        {"currentGoal":["继续研究"],"userPreferences":[],"confirmedConclusions":["已确认结论"],
                        "rejectedOrCorrectedConclusions":[],"referencedObjects":[],"unresolvedQuestions":[]}
                        """)).build());

        service.scheduleIfNeeded(94003L);
        AgentConversationSummaryRecord summary = null;
        for (int attempt = 0; attempt < 100 && summary == null; attempt++) {
            Thread.sleep(25);
            summary = summaryMapper.selectLatest(94003L);
        }

        assertThat(summary).isNotNull();
        assertThat(summary.getSchemaVersion()).isEqualTo(AgentConversationSummaryService.SCHEMA_VERSION);
        assertThat(summary.getSummaryJson()).contains("继续研究", "已确认结论");
        assertThat(messageMapper.selectFinalAfter(94003L, summary.getCoveredThroughMessageId()))
                .hasSize(14)
                .extracting(ResearchMessage::getContent)
                .containsExactly("message content 13", "message content 14", "message content 15",
                        "message content 16", "message content 17", "message content 18",
                        "message content 19", "message content 20", "message content 21",
                        "message content 22", "message content 23", "message content 24",
                        "message content 25", "message content 26");
    }

    @Test
    void staleAsyncSummaryCannotOverwriteANewerRevision() {
        insertSession(94004L, "summary-session-4");
        ResearchMessage boundary = new ResearchMessage();
        boundary.setSessionId(94004L);
        boundary.setMessageKey("summary-boundary-4");
        boundary.setRole("ASSISTANT");
        boundary.setMessageType("CHAT");
        boundary.setMessageStatus("FINAL");
        boundary.setContent("answer");
        messageMapper.insert(boundary);
        AgentConversationSummaryRecord current = service.save(94004L, boundary.getId(),
                AgentConversationSummaryService.SCHEMA_VERSION, "{\"currentGoal\":[\"newer\"]}");

        AgentConversationSummaryRecord result = service.saveIfCurrent(94004L, 0, boundary.getId(),
                "{\"currentGoal\":[\"stale\"]}");

        assertThat(result.getRevision()).isEqualTo(current.getRevision());
        assertThat(summaryMapper.selectLatest(94004L).getSummaryJson()).contains("newer").doesNotContain("stale");
    }

    private void insertSession(long id, String key) {
        jdbcTemplate.update("INSERT INTO research_session "
                        + "(id, session_key, title, session_type, mode, output_language, archived) "
                        + "VALUES (?, ?, ?, 'SINGLE', 'analysis', 'ZH', FALSE)",
                id, key, key);
    }
}
