package com.research.assistant.service.agent.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.service.agent.action.PaperActionResolver;
import com.research.assistant.service.agent.skill.PaperActionSkillTool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.skills.DefaultSkill;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class LangChain4jPaperAgentExecutorTest {

    @Test
    void convertsTheRealPaperActionSchemaBeforeStartingTheModelLoop() {
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                return response("action-1", "paper_action", """
                        {"operations":[
                          {"actionType":"HIGHLIGHT","sourceObjectId":"src-algorithm"},
                          {"actionType":"UNDERLINE","sourceObjectId":"src-formula"}]}
                        """);
            }
        };
        LangChain4jPaperAgentExecutor executor = new LangChain4jPaperAgentExecutor(
                mock(com.research.assistant.service.ai.LangChain4jModelFactory.class), new ObjectMapper());
        AgentToolDefinition actionDefinition = new PaperActionSkillTool(new PaperActionResolver())
                .definitions().get(0);

        AgentFrameworkResult result = executor.execute(model,
                List.of(AgentChatEntry.system("system"), AgentChatEntry.user("执行两个页面操作")),
                List.of(actionDefinition),
                request -> new AgentToolExecution("WAITING_CLIENT", Set.of()));

        assertThat(result.content()).isEqualTo("WAITING_CLIENT");
        assertThat(result.toolCalls()).isEqualTo(1);
    }

    @Test
    void langChain4jOwnsBatchRetrieveThenSubmitLoopWithModelSelectedTools() {
        List<ChatRequest> requests = new ArrayList<>();
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                requests.add(request);
                if (requests.size() == 1) {
                    return response("retrieve-1", "retrieve_paper_evidence", "{\"needs\":[{\"id\":\"question\",\"query\":\"question\"}]}");
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
                        new AgentToolDefinition("retrieve_paper_evidence", "retrieve", objectSchema("needs")),
                        new AgentToolDefinition("submit_answer", "finish", objectSchema("groundingMode"))),
                request -> {
                    executed.add(request.name());
                    return new AgentToolExecution(
                            "submit_answer".equals(request.name()) ? "FINAL" : "{\"source\":\"evidence\"}",
                            Set.of());
                });

        assertThat(result.content()).isEqualTo("FINAL");
        assertThat(result.toolCalls()).isEqualTo(2);
        assertThat(executed).containsExactly("retrieve_paper_evidence", "submit_answer");
        assertThat(requests).hasSize(2).allSatisfy(request ->
                assertThat(request.toolChoice()).isEqualTo(ToolChoice.REQUIRED));
        assertThat(requests.get(1).messages().toString()).contains("evidence");
    }

    @Test
    void recoversWhenProviderReturnsProseAfterReadingEvidence() {
        List<ChatRequest> requests = new ArrayList<>();
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                requests.add(request);
                if (requests.size() == 1) {
                    return response("retrieve-1", "retrieve_paper_evidence",
                            "{\"needs\":[{\"id\":\"question\",\"query\":\"question\"}]}");
                }
                if (requests.size() == 2) {
                    return ChatResponse.builder().aiMessage(AiMessage.from("普通文本回答" )).build();
                }
                return response("submit-1", "submit_answer",
                        "{\"answerBlocks\":[{\"text\":\"结构化回答\",\"sourceObjectIds\":[\"src-1\"]}]}" );
            }
        };
        LangChain4jPaperAgentExecutor executor = new LangChain4jPaperAgentExecutor(
                mock(com.research.assistant.service.ai.LangChain4jModelFactory.class), new ObjectMapper());

        AgentFrameworkResult result = executor.execute(model,
                List.of(AgentChatEntry.system("system"), AgentChatEntry.user("question")),
                List.of(
                        new AgentToolDefinition("retrieve_paper_evidence", "retrieve", objectSchema("needs")),
                        new AgentToolDefinition("submit_answer", "finish", objectSchema("answerBlocks"))),
                request -> new AgentToolExecution(
                        "submit_answer".equals(request.name()) ? "FINAL" : "{\"sources\":[{\"sourceObjectId\":\"src-1\"}]}",
                        "submit_answer".equals(request.name()) ? Set.of("src-1") : Set.of("src-1")));

        assertThat(result.content()).isEqualTo("FINAL");
        assertThat(requests).hasSize(3);
    }

    @Test
    void convertsDirectProseToStructuredSubmission() {
        List<ChatRequest> requests = new ArrayList<>();
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                requests.add(request);
                if (requests.size() > 1) {
                    return response("submit-1", "submit_answer", "{\"answerBlocks\":[]}");
                }
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
                request -> new AgentToolExecution("FINAL", Set.of()));

        assertThat(result.content()).isEqualTo("FINAL");
        assertThat(result.toolCalls()).isEqualTo(1);
        assertThat(requests).hasSize(2);
        assertThat(requests).allSatisfy(request ->
                assertThat(request.toolChoice()).isEqualTo(ToolChoice.REQUIRED));
        assertThat(requests.get(1).messages().toString())
                .contains("没有调用任何工具", "论文画像 Skill", "证据 Skill", "submit_answer");
    }

    @Test
    void asksOnlyForTerminalSubmissionAfterNonTerminalToolsWereAlreadyUsed() {
        List<ChatRequest> requests = new ArrayList<>();
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                requests.add(request);
                if (requests.size() == 1) {
                    return response("retrieve-1", "retrieve_paper_evidence",
                            "{\"needs\":[{\"id\":\"question\",\"query\":\"question\"}]}");
                }
                if (requests.size() == 2) {
                    return ChatResponse.builder().aiMessage(AiMessage.from("普通文本回答")).build();
                }
                return response("submit-1", "submit_answer", "{\"answerBlocks\":[]}");
            }
        };
        LangChain4jPaperAgentExecutor executor = new LangChain4jPaperAgentExecutor(
                mock(com.research.assistant.service.ai.LangChain4jModelFactory.class), new ObjectMapper());

        executor.execute(model,
                List.of(AgentChatEntry.system("system"), AgentChatEntry.user("question")),
                List.of(
                        new AgentToolDefinition("retrieve_paper_evidence", "retrieve", objectSchema("needs")),
                        new AgentToolDefinition("submit_answer", "finish", objectSchema("answerBlocks"))),
                request -> new AgentToolExecution("submit_answer".equals(request.name()) ? "FINAL" : "evidence", Set.of()));

        assertThat(requests).hasSize(3);
        assertThat(requests.get(2).messages().toString())
                .contains("现在立即调用 submit_answer")
                .doesNotContain("刚才没有调用任何工具");
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
                    return new AgentToolExecution("CORRECTED", Set.of());
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
                            "{\"needs\":[{\"id\":\"question-" + call + "\",\"query\":\"question " + call + "\"}]}");
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
                        new AgentToolDefinition("retrieve_paper_evidence", "retrieve", objectSchema("needs")),
                        new AgentToolDefinition("submit_answer", "finish", objectSchema("answerBlocks"))),
                request -> {
                    executed.add(request.name());
                    return new AgentToolExecution(
                            "submit_answer".equals(request.name()) ? "FINAL" : "{\"sources\":[]}", Set.of());
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
                calls.incrementAndGet();
                return response("submit-1", "submit_answer", "{\"answerBlocks\":[]}");
            }
        };
        LangChain4jPaperAgentExecutor executor = new LangChain4jPaperAgentExecutor(
                mock(com.research.assistant.service.ai.LangChain4jModelFactory.class), new ObjectMapper());
        List<AgentModelCallTrace> traces = new ArrayList<>();

        executor.execute(model, List.of(AgentChatEntry.system("secret"), AgentChatEntry.user("question")),
                List.of(new AgentToolDefinition("submit_answer", "finish", objectSchema("answerBlocks"))),
                request -> new AgentToolExecution("unused", Set.of()), traces::add);

        assertThat(traces).hasSize(1);
        assertThat(traces.get(0).ordinal()).isEqualTo(1);
        assertThat(traces.get(0).status()).isEqualTo("COMPLETED");
        assertThat(traces.get(0).messageCount()).isEqualTo(2);
        assertThat(traces.get(0).estimatedPromptTokensBefore()).isPositive();
        assertThat(traces.get(0).estimatedPromptTokens()).isPositive();
        assertThat(traces.get(0).cumulativeEstimatedPromptTokens())
                .isEqualTo(traces.get(0).estimatedPromptTokens());
        assertThat(traces.get(0).maxEstimatedPromptTokens())
                .isEqualTo(traces.get(0).estimatedPromptTokens());
        assertThat(traces.get(0).responseKind()).isEqualTo("TOOL_CALL");
        assertThat(traces.get(0).responseTextCharacters()).isZero();
        assertThat(traces.toString()).doesNotContain("secret", "question", "done");
        assertThat(calls).hasValue(1);
    }

    @Test
    void preservesUsageAndClassifiesTextWhenProtocolRecoveryStillFails() {
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                return ChatResponse.builder()
                        .aiMessage(AiMessage.from("普通文本回答"))
                        .tokenUsage(new TokenUsage(11, 3))
                        .build();
            }
        };
        LangChain4jPaperAgentExecutor executor = new LangChain4jPaperAgentExecutor(
                mock(com.research.assistant.service.ai.LangChain4jModelFactory.class), new ObjectMapper());
        List<AgentModelCallTrace> traces = new ArrayList<>();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> executor.execute(model,
                List.of(AgentChatEntry.system("system"), AgentChatEntry.user("question")),
                List.of(new AgentToolDefinition("submit_answer", "finish", objectSchema("answerBlocks"))),
                request -> new AgentToolExecution("unused", Set.of()), traces::add))
                .isInstanceOf(AgentFrameworkExecutionException.class)
                .hasMessageContaining("ANSWER_SUBMISSION_REQUIRED")
                .satisfies(error -> {
                    AgentFrameworkExecutionException failure = (AgentFrameworkExecutionException) error;
                    assertThat(failure.usage().modelCalls()).isEqualTo(2);
                    assertThat(failure.usage().toolCalls()).isZero();
                    assertThat(failure.usage().promptTokens()).isEqualTo(22);
                    assertThat(failure.usage().completionTokens()).isEqualTo(6);
                });

        assertThat(traces).hasSize(2).allSatisfy(trace -> {
            assertThat(trace.responseKind()).isEqualTo("TEXT");
            assertThat(trace.responseTextCharacters()).isPositive();
            assertThat(trace.toolCount()).isEqualTo(1);
        });
    }

    @Test
    void rejectsAnOversizedModelRequestBeforeCallingTheProvider() {
        AtomicInteger calls = new AtomicInteger();
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                calls.incrementAndGet();
                return response("submit-1", "submit_answer", "{\"answerBlocks\":[]}");
            }
        };
        LangChain4jPaperAgentExecutor executor = new LangChain4jPaperAgentExecutor(
                mock(com.research.assistant.service.ai.LangChain4jModelFactory.class), new ObjectMapper());
        List<AgentModelCallTrace> traces = new ArrayList<>();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> executor.execute(model,
                List.of(AgentChatEntry.system("x".repeat(60_000)), AgentChatEntry.user("question")),
                List.of(new AgentToolDefinition("submit_answer", "finish", objectSchema("answerBlocks"))),
                request -> new AgentToolExecution("unused", Set.of()), traces::add))
                .hasMessageContaining("CONTEXT_BUDGET_EXCEEDED");

        assertThat(calls).hasValue(0);
        assertThat(traces).singleElement().satisfies(trace -> {
            assertThat(trace.status()).isEqualTo("FAILED");
            assertThat(trace.estimatedPromptTokens()).isGreaterThan(16_000);
        });
    }

    @Test
    void attachesToolVisualsToTheNextRequestForTheSameAgent() {
        List<ChatRequest> requests = new ArrayList<>();
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                requests.add(request);
                if (requests.size() == 1) {
                    return response("visual-1", "retrieve_paper_evidence",
                            "{\"needs\":[{\"id\":\"figure\",\"query\":\"figure\",\"includeVisual\":true}]}");
                }
                return ChatResponse.builder().aiMessage(AiMessage.from("看到了图像证据")).build();
            }
        };
        LangChain4jPaperAgentExecutor executor = new LangChain4jPaperAgentExecutor(
                mock(com.research.assistant.service.ai.LangChain4jModelFactory.class), new ObjectMapper());
        AgentVisualContent visual = new AgentVisualContent(
                "src-figure", 4, "FIGURE", "image/png", new byte[]{1, 2, 3}, 10, 10);

        AgentFrameworkResult result = executor.execute(model,
                List.of(AgentChatEntry.system("system"), AgentChatEntry.user("图中是什么？")),
                List.of(new AgentToolDefinition("retrieve_paper_evidence", "retrieve",
                        objectSchema("needs"))),
                request -> new AgentToolExecution("{\"sources\":[]}",
                        Set.of("src-figure"), List.of(visual)));

        assertThat(result.content()).contains("图像证据");
        assertThat(requests).hasSize(2);
        assertThat(requests.get(1).messages().stream()
                .filter(message -> message instanceof dev.langchain4j.data.message.UserMessage)
                .map(message -> (dev.langchain4j.data.message.UserMessage) message)
                .flatMap(message -> message.contents().stream())
                .anyMatch(content -> content instanceof dev.langchain4j.data.message.ImageContent)).isTrue();
        assertThat(requests.get(1).messages().toString())
                .contains("src-figure", "page=4", "完整图注", "逐字核对图注");
    }

    @Test
    void exposesOnlyActivateSkillBeforeActivationAndScopedToolsAfterActivation() {
        List<ChatRequest> requests = new ArrayList<>();
        List<String> activations = new ArrayList<>();
        AtomicInteger modelCalls = new AtomicInteger();
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                requests.add(request);
                if (modelCalls.incrementAndGet() == 1) {
                    return response("activate-1", "activate_skill",
                            "{\"skill_name\":\"paper-evidence\"}");
                }
                if (modelCalls.get() == 2) {
                    return response("evidence-1", "retrieve_paper_evidence",
                            "{\"needs\":[{\"id\":\"method\",\"query\":\"method\"}]}");
                }
                return response("submit-1", "submit_answer", "{\"answerBlocks\":[]}");
            }
        };
        AgentSkillBinding evidence = new AgentSkillBinding(
                DefaultSkill.builder()
                        .name("paper-evidence")
                        .description("Retrieve original evidence and source-linked images")
                        .content("Call retrieve_paper_evidence for exact paper support.")
                        .build(),
                List.of(new AgentToolDefinition("retrieve_paper_evidence", "retrieve",
                        objectSchema("needs"))));
        LangChain4jPaperAgentExecutor executor = new LangChain4jPaperAgentExecutor(
                mock(com.research.assistant.service.ai.LangChain4jModelFactory.class), new ObjectMapper());

        AgentFrameworkResult result = executor.execute(model,
                List.of(AgentChatEntry.system("system"), AgentChatEntry.user("question")),
                List.of(new AgentToolDefinition("submit_answer", "finish", objectSchema("answerBlocks"))),
                List.of(evidence),
                request -> new AgentToolExecution(
                        "submit_answer".equals(request.name()) ? "完成" : "{\"sources\":[]}", Set.of()),
                (id, name, arguments, instructions) -> activations.add(name),
                trace -> { });

        assertThat(result.content()).isEqualTo("完成");
        assertThat(requests).hasSize(3);
        assertThat(toolNames(requests.get(0))).contains("activate_skill", "submit_answer")
                .doesNotContain("retrieve_paper_evidence");
        assertThat(toolNames(requests.get(1))).contains("activate_skill", "retrieve_paper_evidence")
                .doesNotContain("paper_action");
        assertThat(requests.get(1).messages().toString()).contains("Call retrieve_paper_evidence");
        assertThat(activations).containsExactly("paper-evidence");
    }

    @Test
    void treatsARehydratedActivationTranscriptAsAlreadyActivated() {
        List<ChatRequest> requests = new ArrayList<>();
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                requests.add(request);
                if (requests.size() == 1) {
                    return ChatResponse.builder().aiMessage(AiMessage.from("继续使用已有能力")).build();
                }
                return response("submit-1", "submit_answer", "{\"answerBlocks\":[]}");
            }
        };
        AgentSkillBinding evidence = new AgentSkillBinding(
                DefaultSkill.builder()
                        .name("paper-evidence")
                        .description("Retrieve original evidence")
                        .content("Call retrieve_paper_evidence.")
                        .build(),
                List.of(new AgentToolDefinition("retrieve_paper_evidence", "retrieve",
                        objectSchema("needs"))));
        LangChain4jPaperAgentExecutor executor = new LangChain4jPaperAgentExecutor(
                mock(com.research.assistant.service.ai.LangChain4jModelFactory.class), new ObjectMapper());
        ToolExecutionRequest activation = ToolExecutionRequest.builder()
                .id("activation-1").name("activate_skill")
                .arguments("{\"skill_name\":\"paper-evidence\"}").build();

        executor.execute(model,
                List.of(AgentChatEntry.system("system"),
                        AgentChatEntry.assistantTool(activation.id(), activation.name(), activation.arguments()),
                        AgentChatEntry.tool(activation.id(), activation.name(), "Call retrieve_paper_evidence.",
                                java.util.Map.of("activated_skill", "paper-evidence")),
                        AgentChatEntry.user("question")),
                List.of(new AgentToolDefinition("submit_answer", "finish", objectSchema("answerBlocks"))),
                List.of(evidence),
                request -> new AgentToolExecution("unused", Set.of()),
                (id, name, arguments, instructions) -> { },
                trace -> { });

        assertThat(requests).hasSize(2);
        assertThat(toolNames(requests.get(0))).contains("activate_skill", "retrieve_paper_evidence");
    }

    @Test
    void completesProfileThenTwoEvidenceReadsBeforeSubmittingWithoutAnotherModel() {
        List<ChatRequest> requests = new ArrayList<>();
        List<AgentModelCallTrace> traces = new ArrayList<>();
        List<String> activations = new ArrayList<>();
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                requests.add(request);
                return switch (requests.size()) {
                    case 1 -> response("activate-profile", "activate_skill",
                            "{\"skill_name\":\"paper-profile\"}");
                    case 2 -> response("profile-1", "read_paper_profile", "{}");
                    case 3 -> response("activate-evidence", "activate_skill",
                            "{\"skill_name\":\"paper-evidence\"}");
                    case 4 -> response("read-1", "retrieve_paper_evidence", "{}");
                    case 5 -> response("read-2", "retrieve_paper_evidence", "{}");
                    case 6 -> response("submit-1", "submit_answer", "{\"answerBlocks\":[]}");
                    default -> throw new AssertionError("unexpected model call");
                };
            }
        };
        AgentSkillBinding profile = new AgentSkillBinding(
                DefaultSkill.builder().name("paper-profile")
                        .description("Read the cached paper profile")
                        .content("Use read_paper_profile to plan the evidence request.").build(),
                List.of(new AgentToolDefinition("read_paper_profile", "read profile",
                        objectSchema("profile"))));
        AgentSkillBinding evidence = new AgentSkillBinding(
                DefaultSkill.builder().name("paper-evidence")
                        .description("Read original paper evidence")
                        .content("Use retrieve_paper_evidence for original text.").build(),
                List.of(new AgentToolDefinition("retrieve_paper_evidence", "read evidence",
                        objectSchema("needs"))));
        LangChain4jPaperAgentExecutor executor = new LangChain4jPaperAgentExecutor(
                mock(com.research.assistant.service.ai.LangChain4jModelFactory.class), new ObjectMapper());

        AgentFrameworkResult result = executor.execute(model,
                List.of(AgentChatEntry.system("system"), AgentChatEntry.user("explain the paper")),
                List.of(new AgentToolDefinition("submit_answer", "finish", objectSchema("answerBlocks"))),
                List.of(profile, evidence),
                request -> switch (request.name()) {
                    case "read_paper_profile" -> new AgentToolExecution(
                            "{\"status\":\"ready\",\"profile\":\"PROFILE_BODY "
                                    + "PROFILE_BODY ".repeat(1_200) + "\"}", Set.of());
                    case "retrieve_paper_evidence" -> new AgentToolExecution(
                            "{\"status\":\"found\",\"sources\":["
                                    + source(request.id().contains("2") ? "src-second" : "src-first",
                                    request.id().contains("2") ? "SECOND_EVIDENCE_BODY " : "FIRST_EVIDENCE_BODY ")
                                    + "]}",
                            Set.of(request.id().contains("2") ? "src-second" : "src-first"));
                    case "submit_answer" -> new AgentToolExecution("FINAL", Set.of());
                    default -> throw new AssertionError("unexpected tool " + request.name());
                },
                (id, name, arguments, instructions) -> activations.add(name),
                traces::add);

        assertThat(result.content()).isEqualTo("FINAL");
        assertThat(requests).hasSize(6);
        assertThat(activations).containsExactly("paper-profile", "paper-evidence");
        assertThat(traces).hasSize(6).allSatisfy(trace ->
                assertThat(trace.estimatedPromptTokens()).isLessThanOrEqualTo(
                        AgentRunContextHarness.HARD_INPUT_TOKENS));
        assertThat(traces).anyMatch(AgentModelCallTrace::compacted);
        assertThat(requests.get(4).messages().toString())
                .contains("论文画像已读取并消费", "src-first");
        assertThat(requests.get(5).messages().toString())
                .contains("src-first", "src-second");
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

    private static String source(String sourceId, String prefix) {
        return "{\"sourceObjectId\":\"" + sourceId + "\",\"page\":3,"
                + "\"contentType\":\"TEXT\",\"content\":\""
                + prefix + prefix.repeat(1_300) + "\",\"contentComplete\":true}";
    }

    private static List<String> toolNames(ChatRequest request) {
        return request.toolSpecifications().stream().map(specification -> specification.name()).toList();
    }
}
