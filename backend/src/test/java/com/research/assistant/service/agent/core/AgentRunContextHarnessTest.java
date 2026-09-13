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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AgentRunContextHarnessTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void leavesARequestBelowTheSoftTargetUnchanged() {
        ChatRequest request = request(List.of(
                AiMessage.from("stable system"),
                UserMessage.from("question")));
        AgentRunContextHarness harness = new AgentRunContextHarness(objectMapper);

        ChatRequest prepared = harness.prepare(request);

        assertThat(prepared.messages()).isEqualTo(request.messages());
        assertThat(harness.lastMetrics().compacted()).isFalse();
    }

    @Test
    void foldsAConsumedSkillActivationButKeepsItsToolPair() {
        ToolExecutionRequest activation = toolRequest("activate-1", "activate_skill",
                "{\"skill_name\":\"paper-evidence\"}");
        List<ChatMessage> messages = List.of(
                AiMessage.from(activation),
                result("activate-1", "activate_skill", "FULL_SKILL_INSTRUCTIONS ".repeat(2_000)),
                AiMessage.from(toolRequest("read-1", "retrieve_paper_evidence", "{}")),
                result("read-1", "retrieve_paper_evidence", "{\"status\":\"found\",\"sources\":[]}"),
                UserMessage.from("question"));

        List<ChatMessage> prepared = compactAfterSending(messages);

        assertThat(prepared).hasSize(messages.size());
        assertThat(prepared.get(1).toString()).contains("Skill 已激活").doesNotContain("FULL_SKILL_INSTRUCTIONS");
        assertThat(prepared.get(0)).isInstanceOf(AiMessage.class);
        assertThat(((ToolExecutionResultMessage) prepared.get(1)).id()).isEqualTo("activate-1");
    }

    @Test
    void foldsConsumedProfileAfterOriginalPaperTextWasRead() throws Exception {
        List<ChatMessage> messages = List.of(
                AiMessage.from(toolRequest("profile-1", "read_paper_profile", "{}")),
                result("profile-1", "read_paper_profile", "{\"status\":\"ready\",\"profile\":{\"long\":\""
                        + "FULL_PROFILE ".repeat(1_500) + "\"}}"),
                AiMessage.from(toolRequest("read-1", "retrieve_paper_evidence", "{}")),
                result("read-1", "retrieve_paper_evidence", evidence("src-1", "original text")),
                UserMessage.from("question"));

        List<ChatMessage> prepared = compactAfterSending(messages);

        JsonNode compactProfile = objectMapper.readTree(
                ((ToolExecutionResultMessage) prepared.get(1)).text());
        assertThat(compactProfile.path("success").asBoolean()).isTrue();
        assertThat(compactProfile.path("message").asText()).contains("不能作为最终论文证据");
        assertThat(((ToolExecutionResultMessage) prepared.get(1)).text()).doesNotContain("FULL_PROFILE");
    }

    @Test
    void mergesDuplicateSourceObjectsAndKeepsTheFirstBodyOnce() throws Exception {
        List<ChatMessage> messages = List.of(
                AiMessage.from(toolRequest("read-1", "retrieve_paper_evidence", "{}")),
                result("read-1", "retrieve_paper_evidence", evidenceWithNeed("need-1", "src-1", "same body")),
                AiMessage.from(toolRequest("read-2", "retrieve_paper_evidence", "{}")),
                result("read-2", "retrieve_paper_evidence", evidenceWithNeed("need-1", "src-1", "same body")),
                UserMessage.from("question"));

        List<ChatMessage> prepared = compactAfterSending(messages);
        JsonNode first = objectMapper.readTree(((ToolExecutionResultMessage) prepared.get(1)).text());
        JsonNode second = objectMapper.readTree(((ToolExecutionResultMessage) prepared.get(3)).text());

        assertThat(first.path("sources").size()).isEqualTo(1);
        assertThat(first.path("sources").get(0).path("content").asText()).isEqualTo("same body");
        assertThat(second.path("sources").size()).isEqualTo(1);
        assertThat(second.path("sources").get(0).path("sourceObjectId").asText()).isEqualTo("src-1");
        assertThat(second.path("sources").get(0).path("sameSource").asBoolean()).isTrue();
        assertThat(second.path("needs")).isEmpty();
    }

    @Test
    void keepsDifferentSourceObjectsEvenWhenTheirBodiesAreEqual() throws Exception {
        List<ChatMessage> messages = List.of(
                AiMessage.from(toolRequest("read-1", "retrieve_paper_evidence", "{}")),
                result("read-1", "retrieve_paper_evidence", "{\"status\":\"found\",\"sources\":["
                        + source("src-a", "same body") + "," + source("src-b", "same body") + "]}"),
                UserMessage.from("question"));

        JsonNode compact = objectMapper.readTree(
                ((ToolExecutionResultMessage) compactAfterSending(messages).get(1)).text());

        assertThat(compact.path("sources").size()).isEqualTo(2);
        assertThat(compact.path("sources").get(0).path("sourceObjectId").asText()).isEqualTo("src-a");
        assertThat(compact.path("sources").get(1).path("sourceObjectId").asText()).isEqualTo("src-b");
        assertThat(compact.path("sources").get(1).path("sameContentAs").asText()).isEqualTo("src-a");
    }

    @Test
    void doesNotCompactTheLatestToolResultUntilTheModelHasSeenIt() {
        List<ChatMessage> oldMessages = List.of(
                AiMessage.from(toolRequest("read-1", "retrieve_paper_evidence", "{}")),
                result("read-1", "retrieve_paper_evidence", evidence("src-old", "OLD_BODY ".repeat(1_500))),
                UserMessage.from("question"));
        AgentRunContextHarness harness = new AgentRunContextHarness(objectMapper);
        ChatRequest first = request(oldMessages, true);
        harness.prepare(first);
        harness.markRequestSent();

        List<ChatMessage> withLatest = new ArrayList<>(oldMessages);
        withLatest.add(withLatest.size() - 1, AiMessage.from(
                toolRequest("read-2", "retrieve_paper_evidence", "{}")));
        withLatest.add(withLatest.size() - 1,
                result("read-2", "retrieve_paper_evidence", evidence("src-new", "LATEST_BODY ".repeat(1_500))));
        ChatRequest prepared = harness.prepare(request(withLatest, true));

        ToolExecutionResultMessage oldResult = (ToolExecutionResultMessage) prepared.messages().get(1);
        ToolExecutionResultMessage latestResult = (ToolExecutionResultMessage) prepared.messages().get(3);
        assertThat(oldResult.text()).contains("\"contentComplete\":false");
        assertThat(latestResult.text()).contains("LATEST_BODY");
        assertThat(harness.lastMetrics().unconsumedToolResultCount()).isEqualTo(1);
    }

    @Test
    void collapsesAnOlderDuplicateBeforePreservingTheUnconsumedLatestResult() throws Exception {
        String duplicate = evidence("src-1", "DUPLICATE_BODY ".repeat(1_500));
        List<ChatMessage> oldMessages = List.of(
                AiMessage.from(toolRequest("read-1", "retrieve_paper_evidence", "{}")),
                result("read-1", "retrieve_paper_evidence", duplicate),
                UserMessage.from("question"));
        AgentRunContextHarness harness = new AgentRunContextHarness(objectMapper);
        harness.prepare(request(oldMessages, true));
        harness.markRequestSent();

        List<ChatMessage> withLatest = new ArrayList<>(oldMessages);
        withLatest.add(withLatest.size() - 1,
                AiMessage.from(toolRequest("read-2", "retrieve_paper_evidence", "{}")));
        withLatest.add(withLatest.size() - 1,
                result("read-2", "retrieve_paper_evidence", duplicate));
        List<ChatMessage> prepared = harness.prepare(request(withLatest, true)).messages();

        JsonNode oldView = objectMapper.readTree(((ToolExecutionResultMessage) prepared.get(1)).text());
        ToolExecutionResultMessage latest = (ToolExecutionResultMessage) prepared.get(3);
        assertThat(oldView.path("sources").get(0).path("sameSource").asBoolean()).isTrue();
        assertThat(oldView.path("sources").get(0).has("content")).isFalse();
        assertThat(latest.text()).isEqualTo(duplicate);
    }

    @Test
    void preservesEveryToolCallAndResultPairWhileCompacting() {
        List<ChatMessage> messages = List.of(
                AiMessage.from(toolRequest("read-1", "retrieve_paper_evidence", "{}")),
                result("read-1", "retrieve_paper_evidence", evidence("src-1", "body-1 ".repeat(1_500))),
                AiMessage.from(toolRequest("read-2", "retrieve_paper_evidence", "{}")),
                result("read-2", "retrieve_paper_evidence", evidence("src-2", "body-2 ".repeat(1_500))),
                UserMessage.from("question"));

        List<ChatMessage> prepared = compactAfterSending(messages);
        List<String> callIds = prepared.stream().filter(AiMessage.class::isInstance)
                .map(AiMessage.class::cast).flatMap(message -> message.toolExecutionRequests().stream())
                .map(ToolExecutionRequest::id).toList();
        List<String> resultIds = prepared.stream().filter(ToolExecutionResultMessage.class::isInstance)
                .map(ToolExecutionResultMessage.class::cast).map(ToolExecutionResultMessage::id).toList();

        assertThat(callIds).containsExactly("read-1", "read-2");
        assertThat(resultIds).containsExactly("read-1", "read-2");
    }

    @Test
    void retainsSubmitAnswerSourceIdsAndEvidenceBodyInTheModelView() throws Exception {
        List<ChatMessage> messages = List.of(
                AiMessage.from(toolRequest("read-1", "retrieve_paper_evidence", "{}")),
                result("read-1", "retrieve_paper_evidence", evidence("src-required", "required body")),
                AiMessage.from(toolRequest("submit-1", "submit_answer",
                        "{\"answerBlocks\":[{\"sourceObjectIds\":[\"src-required\"]}]}")),
                result("submit-1", "submit_answer", "{\"status\":\"error\",\"message\":\"retry\"}"),
                UserMessage.from("question"));

        List<ChatMessage> prepared = compactAfterSending(messages);
        String evidenceText = ((ToolExecutionResultMessage) prepared.get(1)).text();

        assertThat(evidenceText).contains("src-required", "required body");
        assertThat(((AiMessage) prepared.get(2)).toolExecutionRequests().get(0).arguments())
                .contains("src-required");
    }

    @Test
    void onlyChangesTheTemporaryRequestAndLeavesOriginalToolAuditTextUntouched() {
        String raw = evidence("src-1", "AUDIT_BODY ".repeat(1_500));
        ToolExecutionResultMessage original = result("read-1", "retrieve_paper_evidence", raw);
        List<ChatMessage> messages = List.of(
                AiMessage.from(toolRequest("read-1", "retrieve_paper_evidence", "{}")), original,
                UserMessage.from("question"));
        AgentRunContextHarness harness = new AgentRunContextHarness(objectMapper);
        harness.prepare(request(messages, true));
        harness.markRequestSent();

        harness.prepare(request(messages, true));

        assertThat(original.text()).isEqualTo(raw);
    }

    @Test
    void compactsOnlyTheLongDirectResponseDuringProtocolRecovery() {
        List<ChatMessage> messages = List.of(
                UserMessage.from("question"),
                AiMessage.from("LONG_PROSE ".repeat(2_000)),
                UserMessage.from(
                        "[协议恢复] 请调用 submit_answer"));

        List<ChatMessage> prepared = compactAfterSending(messages);

        assertThat(prepared.get(1).toString()).contains("协议恢复状态").doesNotContain("LONG_PROSE");
        assertThat(prepared.get(2).toString()).contains("[协议恢复]");
    }

    private List<ChatMessage> compactAfterSending(List<ChatMessage> messages) {
        AgentRunContextHarness harness = new AgentRunContextHarness(objectMapper);
        harness.prepare(request(messages, true));
        harness.markRequestSent();
        return harness.prepare(request(messages, true)).messages();
    }

    private ChatRequest request(List<ChatMessage> messages) {
        return request(messages, false);
    }

    private ChatRequest request(List<ChatMessage> messages, boolean pressure) {
        String description = pressure ? "finish" + "schema-pressure ".repeat(7_000) : "finish";
        return ChatRequest.builder().messages(messages)
                .toolSpecifications(ToolSpecification.fromJson(
                        "{\"name\":\"submit_answer\",\"description\":\""
                                + description + "\","
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
        return "{\"status\":\"found\",\"sources\":[" + source(sourceId, content) + "]}";
    }

    private static String evidenceWithNeed(String needId, String sourceId, String content) {
        return "{\"status\":\"found\",\"sources\":[" + source(sourceId, content)
                + "],\"evidenceNeeds\":[{\"needId\":\"" + needId
                + "\",\"retrievalStatus\":\"found\",\"sourceObjectIds\":[\""
                + sourceId + "\"]}]}";
    }

    private static String source(String sourceId, String content) {
        return "{\"sourceObjectId\":\"" + sourceId + "\",\"page\":3,"
                + "\"contentType\":\"TEXT\",\"content\":\""
                + content.replace("\\", "\\\\").replace("\"", "\\\"") + "\","
                + "\"contentComplete\":true}";
    }
}
