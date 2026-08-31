package com.research.assistant.service.agent.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class LangChain4jPaperAgentExecutorTest {

    @Test
    void langChain4jOwnsBatchRetrieveThenSubmitLoopWithModelSelectedTools() {
        List<ChatRequest> requests = new ArrayList<>();
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                requests.add(request);
                if (requests.size() == 1) {
                    return response("retrieve-1", "retrieve_paper_evidence", "{\"searches\":[{\"query\":\"question\"}]}");
                }
                return response("submit-1", "submit_answer",
                        "{\"groundingMode\":\"GENERAL_KNOWLEDGE\",\"answerBlocks\":[{\"text\":\"回答\",\"sourceObjectIds\":[]}]}");
            }
        };
        LangChain4jPaperAgentExecutor executor = new LangChain4jPaperAgentExecutor(
                mock(com.research.assistant.service.ai.LangChain4jModelFactory.class), new ObjectMapper());
        List<String> executed = new ArrayList<>();

        AgentFrameworkResult result = executor.execute(model,
                List.of(AgentChatEntry.system("system"), AgentChatEntry.user("question")),
                List.of(
                        new AgentToolDefinition("retrieve_paper_evidence", "retrieve", objectSchema("searches")),
                        new AgentToolDefinition("submit_answer", "finish", objectSchema("groundingMode"))),
                request -> {
                    executed.add(request.name());
                    return "submit_answer".equals(request.name()) ? "FINAL" : "{\"source\":\"evidence\"}";
                });

        assertThat(result.content()).isEqualTo("FINAL");
        assertThat(result.toolCalls()).isEqualTo(2);
        assertThat(executed).containsExactly("retrieve_paper_evidence", "submit_answer");
        assertThat(requests).hasSize(2).allSatisfy(request ->
                assertThat(request.toolChoice()).isEqualTo(ToolChoice.AUTO));
        assertThat(requests.get(1).messages().toString()).contains("evidence");
    }

    @Test
    void allowsModelToAnswerDirectlyWithoutCallingAResearchTool() {
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                return ChatResponse.builder()
                        .aiMessage(AiMessage.from("冒泡排序平均时间复杂度为 O(n^2)。"))
                        .build();
            }
        };
        LangChain4jPaperAgentExecutor executor = new LangChain4jPaperAgentExecutor(
                mock(com.research.assistant.service.ai.LangChain4jModelFactory.class), new ObjectMapper());

        AgentFrameworkResult result = executor.execute(model,
                List.of(AgentChatEntry.system("system"), AgentChatEntry.user("复杂度？")),
                List.of(new AgentToolDefinition("submit_answer", "finish", objectSchema("answerBlocks"))),
                request -> "unexpected");

        assertThat(result.content()).contains("冒泡排序");
        assertThat(result.toolCalls()).isZero();
    }

    @Test
    void returnsValidationFailureToTheModelForOneCorrectedSubmission() {
        List<ChatRequest> requests = new ArrayList<>();
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                requests.add(request);
                return response("submit-" + requests.size(), "submit_answer",
                        "{\"groundingMode\":\"GENERAL_KNOWLEDGE\",\"answerBlocks\":[{\"text\":\"回答\",\"sourceObjectIds\":[]}]}");
            }
        };
        LangChain4jPaperAgentExecutor executor = new LangChain4jPaperAgentExecutor(
                mock(com.research.assistant.service.ai.LangChain4jModelFactory.class), new ObjectMapper());
        AtomicInteger attempts = new AtomicInteger();

        AgentFrameworkResult result = executor.execute(model,
                List.of(AgentChatEntry.system("system"), AgentChatEntry.user("question")),
                List.of(new AgentToolDefinition("submit_answer", "finish", objectSchema("groundingMode"))),
                request -> {
                    if (attempts.incrementAndGet() == 1) throw new IllegalArgumentException("evidence invalid");
                    return "CORRECTED";
                });

        assertThat(result.content()).isEqualTo("CORRECTED");
        assertThat(attempts).hasValue(2);
        assertThat(requests).hasSize(2);
        assertThat(requests.get(1).messages().toString()).contains("evidence invalid");
    }

    @Test
    void allowsTheAgentToTakeSeveralToolRoundsBeforeSubmittingAnAnswer() {
        AtomicInteger modelCalls = new AtomicInteger();
        List<String> executed = new ArrayList<>();
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                int call = modelCalls.incrementAndGet();
                if (call <= 3) {
                    return response("retrieve-" + call, "retrieve_paper_evidence",
                            "{\"searches\":[{\"query\":\"question " + call + "\"}]}");
                }
                return response("submit-final", "submit_answer",
                        "{\"answerBlocks\":[{\"text\":\"最终回答\",\"sourceObjectIds\":[]}]}" );
            }
        };
        LangChain4jPaperAgentExecutor executor = new LangChain4jPaperAgentExecutor(
                mock(com.research.assistant.service.ai.LangChain4jModelFactory.class), new ObjectMapper());

        AgentFrameworkResult result = executor.execute(model,
                List.of(AgentChatEntry.system("system"), AgentChatEntry.user("question")),
                List.of(
                        new AgentToolDefinition("retrieve_paper_evidence", "retrieve", objectSchema("searches")),
                        new AgentToolDefinition("submit_answer", "finish", objectSchema("answerBlocks"))),
                request -> {
                    executed.add(request.name());
                    return "submit_answer".equals(request.name()) ? "FINAL" : "{\"sources\":[]}";
                });

        assertThat(result.content()).isEqualTo("FINAL");
        assertThat(executed).containsExactly("retrieve_paper_evidence", "retrieve_paper_evidence",
                "retrieve_paper_evidence", "submit_answer");
        assertThat(modelCalls).hasValue(4);
    }

    @Test
    void reportsEachModelCallWithoutPromptOrResponseContent() {
        AtomicInteger calls = new AtomicInteger();
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                return ChatResponse.builder().aiMessage(AiMessage.from("done")).build();
            }
        };
        LangChain4jPaperAgentExecutor executor = new LangChain4jPaperAgentExecutor(
                mock(com.research.assistant.service.ai.LangChain4jModelFactory.class), new ObjectMapper());
        List<AgentModelCallTrace> traces = new ArrayList<>();

        executor.execute(model, List.of(AgentChatEntry.system("secret"), AgentChatEntry.user("question")),
                List.of(new AgentToolDefinition("submit_answer", "finish", objectSchema("answerBlocks"))),
                request -> "unused", traces::add);

        assertThat(traces).hasSize(1);
        assertThat(traces.get(0).ordinal()).isEqualTo(1);
        assertThat(traces.get(0).status()).isEqualTo("COMPLETED");
        assertThat(traces.get(0).messageCount()).isEqualTo(2);
        assertThat(traces.toString()).doesNotContain("secret", "question", "done");
        assertThat(calls).hasValue(0);
    }

    @Test
    void retriesOnlyTransientToolFailures() {
        assertThat(LangChain4jPaperAgentExecutor.isRetryable(
                new IllegalArgumentException("source was not read"))).isFalse();
        assertThat(LangChain4jPaperAgentExecutor.isRetryable(
                new IllegalStateException("429 rate limit"))).isTrue();
    }

    private static ChatResponse response(String id, String name, String arguments) {
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id(id).name(name).arguments(arguments).build();
        return ChatResponse.builder().aiMessage(AiMessage.from(request)).build();
    }

    private static String objectSchema(String required) {
        return "{\"type\":\"object\",\"properties\":{\"" + required
                + "\":{\"type\":\"string\"}},\"required\":[\"" + required
                + "\"],\"additionalProperties\":false}";
    }
}
