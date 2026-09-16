package com.research.assistant.service.agent.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentRunContextHarnessTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void projectsTheSameTranscriptIdenticallyOnEveryRequest() {
        String raw = evidence("src-1", "AUDIT_BODY ".repeat(400));
        ToolExecutionResultMessage durable = result("read-1", "retrieve_paper_evidence", raw);
        ChatRequest request = request(List.of(
                AiMessage.from(toolRequest("read-1", "retrieve_paper_evidence", "{}")),
                durable, UserMessage.from("question")));
        AgentRunContextHarness harness = new AgentRunContextHarness(objectMapper);

        ChatRequest first = harness.prepare(request);
        ChatRequest second = harness.prepare(request);

        assertThat(first.messages()).isEqualTo(second.messages());
        assertThat(((ToolExecutionResultMessage) first.messages().get(1)).text())
                .contains("evidence_cards", "src-1").doesNotContain("fullText");
        assertThat(durable.text()).isEqualTo(raw);
    }

    @Test
    void boundsRepeatedEvidenceAndKeepsNewestCardsReadable() throws Exception {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(UserMessage.from("summarize"));
        for (int index = 1; index <= 10; index++) {
            String id = "read-" + index;
            messages.add(AiMessage.from(toolRequest(id, "retrieve_paper_evidence", "{}")));
            messages.add(result(id, "retrieve_paper_evidence",
                    evidence("src-" + index, ("BODY_" + index + " ").repeat(700))));
        }
        ChatRequest prepared = new AgentRunContextHarness(objectMapper).prepare(request(messages));

        assertThat(AgentRunContextHarness.estimatedRequestTokens(prepared))
                .isLessThanOrEqualTo(AgentRunContextHarness.INPUT_BUDGET_TOKENS);
        ToolExecutionResultMessage newestMessage = prepared.messages().stream()
                .filter(ToolExecutionResultMessage.class::isInstance)
                .map(ToolExecutionResultMessage.class::cast)
                .filter(message -> "read-10".equals(message.id()))
                .findFirst().orElseThrow();
        JsonNode newest = objectMapper.readTree(newestMessage.text());
        assertThat(newest.at("/sources/0/sourceObjectId").asText()).isEqualTo("src-10");
        assertThat(newest.at("/sources/0/content").asText()).contains("BODY_10");
        assertThat(prepared.messages().toString())
                .contains("agent_working_state", "src-1", "src-10");
        assertThat(prepared.messages()).hasSizeLessThan(messages.size());
    }

    @Test
    void projectsProfileAsBoundedPlanningState() throws Exception {
        String profile = "{\"status\":\"ready\",\"paperId\":9,\"profile\":{"
                + "\"title\":\"Paper\",\"methodSummary\":\"" + "METHOD ".repeat(1200) + "\"},"
                + "\"candidateSourceObjectIds\":[\"src-1\"]}";
        ChatRequest prepared = new AgentRunContextHarness(objectMapper).prepare(request(List.of(
                AiMessage.from(toolRequest("profile-1", "read_paper_profile", "{}")),
                result("profile-1", "read_paper_profile", profile), UserMessage.from("question"))));

        JsonNode view = objectMapper.readTree(((ToolExecutionResultMessage) prepared.messages().get(1)).text());
        assertThat(view.at("/profile/title").asText()).isEqualTo("Paper");
        assertThat(view.at("/profile/methodSummary").asText().length()).isLessThanOrEqualTo(480);
        assertThat(view.path("modelView").asText()).isEqualTo("paper_profile");
    }

    @Test
    void foldedWorkingStateKeepsTheOriginalProfileAfterAnIdempotentRepeat() throws Exception {
        String profile = "{\"status\":\"ready\",\"paperId\":9,\"profile\":{\"title\":\"Paper\"}}";
        List<ChatMessage> messages = List.of(
                UserMessage.from("question"),
                AiMessage.from(toolRequest("profile-1", "read_paper_profile", "{}")),
                result("profile-1", "read_paper_profile", profile),
                AiMessage.from(toolRequest("profile-2", "read_paper_profile", "{}")),
                result("profile-2", "read_paper_profile", "{\"status\":\"already_available\"}"),
                AiMessage.from(toolRequest("read-1", "retrieve_paper_evidence", "{}")),
                result("read-1", "retrieve_paper_evidence", evidence("src-1", "first")),
                AiMessage.from(toolRequest("read-2", "retrieve_paper_evidence", "{}")),
                result("read-2", "retrieve_paper_evidence", evidence("src-2", "second")));

        ChatRequest prepared = new AgentRunContextHarness(objectMapper).prepare(request(messages));

        ToolExecutionResultMessage workingState = prepared.messages().stream()
                .filter(ToolExecutionResultMessage.class::isInstance)
                .map(ToolExecutionResultMessage.class::cast)
                .filter(message -> "agent_working_state".equals(message.toolName()))
                .findFirst().orElseThrow();
        assertThat(objectMapper.readTree(workingState.text())
                .at("/paperProfile/profile/title").asText()).isEqualTo("Paper");
    }

    @Test
    void preservesProtocolStateNeededByTheNextModelStep() throws Exception {
        String finish = "{\"status\":\"ready_for_answer\",\"citationSources\":[{"
                + "\"label\":\"S1\",\"sourceObjectId\":\"src-1\",\"contentType\":\"TEXT\"}],"
                + "\"instructions\":\"Write final Markdown with [S1].\"}";
        String action = "{\"status\":\"action_planned\","
                + "\"actionCount\":2,\"instructions\":\"Call finish_research after research.\"}";
        ChatRequest prepared = new AgentRunContextHarness(objectMapper).prepare(request(List.of(
                AiMessage.from(toolRequest("action-1", "paper_action", "{}")),
                result("action-1", "paper_action", action),
                AiMessage.from(toolRequest("finish-1", "finish_research", "{}")),
                result("finish-1", "finish_research", finish))));

        JsonNode actionView = objectMapper.readTree(
                ((ToolExecutionResultMessage) prepared.messages().get(1)).text());
        JsonNode finishView = objectMapper.readTree(
                ((ToolExecutionResultMessage) prepared.messages().get(3)).text());
        assertThat(actionView.path("status").asText()).isEqualTo("action_planned");
        assertThat(actionView.has("responseMode")).isFalse();
        assertThat(actionView.path("actionCount").asInt()).isEqualTo(2);
        assertThat(finishView.path("status").asText()).isEqualTo("ready_for_answer");
        assertThat(finishView.at("/citationSources/0/sourceObjectId").asText()).isEqualTo("src-1");
        assertThat(finishView.path("instructions").asText()).contains("[S1]");
    }

    @Test
    void dropsOnlyOldCompleteTurnsWhenTheBudgetRequiresIt() {
        List<ChatMessage> messages = List.of(
                UserMessage.from("old question"), AiMessage.from("OLD_HISTORY ".repeat(5000)),
                UserMessage.from("current question"),
                AiMessage.from(toolRequest("read-current", "retrieve_paper_evidence", "{}")),
                result("read-current", "retrieve_paper_evidence", evidence("src-current", "CURRENT_EVIDENCE")));

        ChatRequest prepared = new AgentRunContextHarness(objectMapper).prepare(request(messages));

        assertThat(prepared.messages().toString())
                .doesNotContain("OLD_HISTORY").contains("current question", "src-current", "CURRENT_EVIDENCE");
    }

    @Test
    void rejectsOnlyWhenRequiredCurrentInputCannotFit() {
        ChatRequest request = request(List.of(UserMessage.from("CURRENT ".repeat(20_000))));

        assertThatThrownBy(() -> new AgentRunContextHarness(objectMapper).prepare(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("CONTEXT_BUDGET_EXCEEDED");
    }

    private static ChatRequest request(List<ChatMessage> messages) {
        return ChatRequest.builder().messages(messages)
                .toolSpecifications(ToolSpecification.fromJson(
                        "{\"name\":\"finish_research\",\"description\":\"finish\","
                                + "\"parameters\":{\"type\":\"object\",\"additionalProperties\":false}}"))
                .build();
    }

    private static ToolExecutionRequest toolRequest(String id, String name, String arguments) {
        return ToolExecutionRequest.builder().id(id).name(name).arguments(arguments).build();
    }

    private static ToolExecutionResultMessage result(String id, String toolName, String text) {
        return ToolExecutionResultMessage.builder().id(id).toolName(toolName).text(text).build();
    }

    private static String evidence(String sourceId, String content) {
        return "{\"status\":\"found\",\"sources\":[{\"sourceObjectId\":\"" + sourceId
                + "\",\"page\":3,\"contentType\":\"TEXT\",\"content\":\""
                + content.replace("\\", "\\\\").replace("\"", "\\\"")
                + "\",\"contentComplete\":true}]}";
    }
}
