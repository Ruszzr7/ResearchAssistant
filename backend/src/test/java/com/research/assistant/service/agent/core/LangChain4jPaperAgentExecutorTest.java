package com.research.assistant.service.agent.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.service.agent.action.PaperActionResolver;
import com.research.assistant.service.agent.skill.PaperActionSkillTool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.skills.DefaultSkill;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class LangChain4jPaperAgentExecutorTest {

    private final LangChain4jPaperAgentExecutor executor = new LangChain4jPaperAgentExecutor(
            mock(com.research.assistant.service.ai.LangChain4jModelFactory.class), new ObjectMapper());

    @Test
    void finishResearchIsTheOnlyTransitionToPlainMarkdown() {
        List<ChatRequest> requests = new ArrayList<>();
        ChatModel model = scripted(request -> {
            requests.add(request);
            return requests.size() == 1
                    ? response("finish-1", "finish_research", "{}")
                    : ChatResponse.builder().aiMessage(AiMessage.from("最终 Markdown 回答")).build();
        });

        AgentFrameworkResult result = executor.execute(model, baseMessages(),
                List.of(finishDefinition()),
                request -> new AgentToolExecution("{\"status\":\"ready_for_answer\"}", Set.of()));

        assertThat(result.content()).isEqualTo("最终 Markdown 回答");
        assertThat(requests).hasSize(2);
        assertThat(requests.get(0).toolChoice()).isEqualTo(ToolChoice.AUTO);
        assertThat(requests.get(1).toolChoice()).isEqualTo(ToolChoice.NONE);
        assertThat(requests.get(1).toolSpecifications()).isEmpty();
    }

    @Test
    void actionPlanContinuesToFinishBeforeReturningContent() {
        List<ChatRequest> requests = new ArrayList<>();
        ChatModel model = scripted(request -> {
            requests.add(request);
            return switch (requests.size()) {
                case 1 -> response("action-1", "paper_action",
                        "{\"actionType\":\"HIGHLIGHT\",\"sourceObjectId\":\"src-1\"}");
                case 2 -> response("finish-1", "finish_research", "{}");
                default -> ChatResponse.builder().aiMessage(AiMessage.from("有依据的回答 [S1]")).build();
            };
        });
        List<String> executed = new ArrayList<>();

        AgentFrameworkResult result = executor.execute(model, baseMessages(),
                List.of(actionDefinition(), finishDefinition()), request -> {
                    executed.add(request.name());
                    return new AgentToolExecution("finish_research".equals(request.name())
                            ? "{\"status\":\"ready_for_answer\"}"
                            : "{\"status\":\"action_planned\"}", Set.of());
                });

        assertThat(result.content()).isEqualTo("有依据的回答 [S1]");
        assertThat(executed).containsExactly("paper_action", "finish_research");
        assertThat(requests).hasSize(3);
        assertThat(requests.get(0).toolChoice()).isEqualTo(ToolChoice.AUTO);
        assertThat(requests.get(1).toolChoice()).isEqualTo(ToolChoice.AUTO);
        assertThat(requests.get(2).toolChoice()).isEqualTo(ToolChoice.NONE);
    }

    @Test
    void unsatisfiedOutputObligationKeepsToolsEnabled() {
        List<ChatRequest> requests = new ArrayList<>();
        ChatModel model = scripted(request -> {
            requests.add(request);
            return switch (requests.size()) {
                case 1 -> response("finish-early", "finish_research", "{}");
                case 2 -> response("action-1", "paper_action",
                        "{\"actionType\":\"JUMP\",\"sourceObjectId\":\"src-1\"}");
                case 3 -> response("finish-final", "finish_research", "{}");
                default -> ChatResponse.builder().aiMessage(AiMessage.from("操作计划完成")).build();
            };
        });
        AtomicInteger finishCalls = new AtomicInteger();

        executor.execute(model, baseMessages(), List.of(actionDefinition(), finishDefinition()), request -> {
            if ("finish_research".equals(request.name()) && finishCalls.getAndIncrement() == 0) {
                return new AgentToolExecution("{\"status\":\"action_plan_required\"}", Set.of());
            }
            return new AgentToolExecution("finish_research".equals(request.name())
                    ? "{\"status\":\"ready_for_answer\"}"
                    : "{\"status\":\"action_planned\"}", Set.of());
        });

        assertThat(requests).hasSize(4);
        assertThat(requests.get(1).toolChoice()).isEqualTo(ToolChoice.AUTO);
        assertThat(requests.get(3).toolChoice()).isEqualTo(ToolChoice.NONE);
    }

    @Test
    void completedSkillToolRemainsAvailableForAgentDecisions() {
        List<ChatRequest> requests = new ArrayList<>();
        ChatModel model = scripted(request -> {
            requests.add(request);
            return switch (requests.size()) {
                case 1 -> response("activate-1", "activate_skill",
                        "{\"skill_name\":\"paper-evidence\"}");
                case 2 -> response("evidence-1", "retrieve_paper_evidence", "{}");
                case 3 -> response("finish-1", "finish_research", "{}");
                default -> ChatResponse.builder().aiMessage(AiMessage.from("当前无法定位该段落。")).build();
            };
        });
        AgentSkillBinding evidence = new AgentSkillBinding(DefaultSkill.builder()
                .name("paper-evidence").description("读取论文证据").content("读取后回答").build(),
                List.of(new AgentToolDefinition("retrieve_paper_evidence", "retrieve", "{\"type\":\"object\"}")));

        AgentFrameworkResult result = executor.execute(model, baseMessages(), List.of(finishDefinition()),
                List.of(evidence), request -> "retrieve_paper_evidence".equals(request.name())
                        ? new AgentToolExecution("{\"status\":\"need_stopped\"}", Set.of(), List.of())
                        : new AgentToolExecution("{\"status\":\"ready_for_answer\"}", Set.of()),
                (id, name, arguments, instructions) -> { }, call -> { });

        assertThat(result.content()).isEqualTo("当前无法定位该段落。");
        assertThat(requests).hasSize(4);
        assertThat(requests.get(1).toolSpecifications()).extracting(spec -> spec.name())
                .contains("retrieve_paper_evidence");
        assertThat(requests.get(2).toolSpecifications()).extracting(spec -> spec.name())
                .contains("retrieve_paper_evidence");
    }

    @Test
    void repeatedEvidenceCallsStayWithinTheSingleContextBudget() {
        List<ChatRequest> requests = new ArrayList<>();
        ChatModel model = scripted(request -> {
            requests.add(request);
            int call = requests.size();
            if (call <= 8) return response("read-" + call, "retrieve_paper_evidence", "{}");
            if (call == 9) return response("finish-1", "finish_research", "{}");
            return ChatResponse.builder().aiMessage(AiMessage.from("回答")).build();
        });

        AgentFrameworkResult result = executor.execute(model, baseMessages(),
                List.of(evidenceDefinition(), finishDefinition()), request -> {
                    if ("finish_research".equals(request.name())) {
                        return new AgentToolExecution("{\"status\":\"ready_for_answer\"}", Set.of());
                    }
                    String sourceId = request.id().replace("read-", "src-");
                    return new AgentToolExecution(evidence(sourceId, "BODY ".repeat(1200)), Set.of(sourceId));
                });

        assertThat(result.content()).isEqualTo("回答");
        assertThat(requests).hasSize(10).allSatisfy(request ->
                assertThat(AgentRunContextHarness.estimatedRequestTokens(request))
                        .isLessThanOrEqualTo(AgentRunContextHarness.INPUT_BUDGET_TOKENS));
    }

    @Test
    void reservesOneNoToolCallAfterTenResearchRounds() {
        List<ChatRequest> requests = new ArrayList<>();
        ChatModel model = scripted(request -> {
            requests.add(request);
            int call = requests.size();
            if (call <= 9) return response("read-" + call, "retrieve_paper_evidence", "{}");
            if (call == 10) return response("finish-1", "finish_research", "{}");
            return ChatResponse.builder().aiMessage(AiMessage.from("最终回答")).build();
        });

        AgentFrameworkResult result = executor.execute(model, baseMessages(),
                List.of(evidenceDefinition(), finishDefinition()), request -> {
                    if ("finish_research".equals(request.name())) {
                        return new AgentToolExecution("{\"status\":\"ready_for_answer\"}", Set.of());
                    }
                    String sourceId = request.id().replace("read-", "src-");
                    return new AgentToolExecution(evidence(sourceId, "BODY ".repeat(400)), Set.of(sourceId));
                });

        assertThat(result.content()).isEqualTo("最终回答");
        assertThat(requests).hasSize(11);
        assertThat(requests.subList(0, 10)).allSatisfy(request ->
                assertThat(request.toolChoice()).isEqualTo(ToolChoice.AUTO));
        assertThat(requests.get(10).toolChoice()).isEqualTo(ToolChoice.NONE);
        assertThat(requests.get(10).toolSpecifications()).isEmpty();
    }

    @Test
    void providerExplorationPastBudgetFallsBackToAVisibleAnswer() {
        List<ChatRequest> requests = new ArrayList<>();
        ChatModel model = scripted(request -> {
            requests.add(request);
            int call = requests.size();
            if (call <= 11) return response("read-" + call, "retrieve_paper_evidence", "{}");
            return ChatResponse.builder().aiMessage(AiMessage.from("基于已读证据的最终回答")).build();
        });

        AgentFrameworkResult result = executor.execute(model, baseMessages(),
                List.of(evidenceDefinition(), finishDefinition()), request ->
                        new AgentToolExecution(evidence("src-" + request.id(), "evidence"), Set.of()));

        assertThat(result.content()).isEqualTo("基于已读证据的最终回答");
        assertThat(requests).hasSize(12);
        assertThat(requests.get(11).toolChoice()).isEqualTo(ToolChoice.NONE);
        assertThat(requests.get(11).toolSpecifications()).isEmpty();
    }

    @Test
    void oversizedCurrentInputIsRejectedByTheContextOwnerBeforeProviderCall() {
        AtomicInteger providerCalls = new AtomicInteger();
        ChatModel model = scripted(request -> {
            providerCalls.incrementAndGet();
            return ChatResponse.builder().aiMessage(AiMessage.from("unexpected")).build();
        });

        assertThatThrownBy(() -> executor.execute(model,
                List.of(AgentChatEntry.system("system"), AgentChatEntry.user("CURRENT ".repeat(20_000))),
                List.of(finishDefinition()), request -> new AgentToolExecution("{}", Set.of())))
                .isInstanceOf(AgentFrameworkExecutionException.class)
                .hasRootCauseMessage("CONTEXT_BUDGET_EXCEEDED");
        assertThat(providerCalls).hasValue(0);
    }

    @Test
    void actionSchemaContainsOnlyTheOperationPlan() {
        AgentToolDefinition definition = actionDefinition();

        assertThat(definition.parametersJsonSchema())
                .contains("actionType", "sourceObjectId", "operations")
                .doesNotContain("responseMode", "answerBlocks", "\"answer\"");
    }

    @Test
    void retriesOnlyTransientProviderAndToolFailures() {
        assertThat(LangChain4jPaperAgentExecutor.isRetryable(
                new IllegalArgumentException("source was not read"))).isFalse();
        assertThat(LangChain4jPaperAgentExecutor.isRetryable(
                new IllegalStateException("429 rate limit"))).isTrue();
    }

    private static List<AgentChatEntry> baseMessages() {
        return List.of(AgentChatEntry.system("system"), AgentChatEntry.user("question"));
    }

    private static ChatModel scripted(Function<ChatRequest, ChatResponse> script) {
        return new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                return script.apply(request);
            }
        };
    }

    private static AgentToolDefinition finishDefinition() {
        return new AgentToolDefinition("finish_research", "finish",
                "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}");
    }

    private static AgentToolDefinition actionDefinition() {
        return new PaperActionSkillTool(new PaperActionResolver()).definitions().get(0);
    }

    private static AgentToolDefinition evidenceDefinition() {
        return new AgentToolDefinition("retrieve_paper_evidence", "read",
                "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}");
    }

    private static ChatResponse response(String id, String name, String arguments) {
        return ChatResponse.builder().aiMessage(AiMessage.from(ToolExecutionRequest.builder()
                .id(id).name(name).arguments(arguments).build())).build();
    }

    private static String evidence(String sourceId, String content) {
        return "{\"status\":\"found\",\"sources\":[{\"sourceObjectId\":\"" + sourceId
                + "\",\"page\":3,\"contentType\":\"TEXT\",\"content\":\""
                + content + "\",\"contentComplete\":true}]}";
    }
}
