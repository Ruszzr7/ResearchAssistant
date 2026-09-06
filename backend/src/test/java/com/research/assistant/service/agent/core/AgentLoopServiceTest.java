package com.research.assistant.service.agent.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.dto.agent.AgentExplicitAction;
import com.research.assistant.dto.agent.AgentTurnInput;
import com.research.assistant.dto.agent.AgentTurnResult;
import com.research.assistant.entity.AgentRunRecord;
import com.research.assistant.entity.AgentToolCallRecord;
import com.research.assistant.entity.AgentTurnRecord;
import com.research.assistant.mapper.ResearchMessageMapper;
import com.research.assistant.service.agent.runtime.AgentModelSnapshot;
import com.research.assistant.service.agent.runtime.AgentRunStatus;
import com.research.assistant.service.agent.runtime.AgentRuntimeService;
import com.research.assistant.service.agent.source.GroundEvidenceService;
import com.research.assistant.service.agent.source.PaperSourceCatalog;
import com.research.assistant.service.agent.source.SourceContentType;
import com.research.assistant.service.agent.source.SourceLocator;
import com.research.assistant.service.agent.source.SourceObject;
import com.research.assistant.service.agent.action.ActionTicketService;
import com.research.assistant.service.agent.action.PaperActionResolver;
import com.research.assistant.service.agent.action.ActionTarget;
import com.research.assistant.service.agent.action.PaperActionType;
import com.research.assistant.service.agent.capability.AiCapabilityService;
import com.research.assistant.service.pdf.layout.EvidenceLocator;
import com.research.assistant.service.pdf.layout.NormalizedBoundingBox;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentLoopServiceTest {
    private AgentRuntimeService runtime;
    private AgentContextAssembler assembler;
    private AgentModelSnapshotService snapshots;
    private PaperReadToolRegistry tools;
    private ResearchMessageMapper messages;
    private FakeGateway gateway;
    private PaperActionResolver actionResolver;
    private ActionTicketService ticketService;
    private AgentLoopService service;
    private AgentTurnRecord turn;
    private AgentRunRecord run;

    @BeforeEach
    void setUp() {
        runtime = mock(AgentRuntimeService.class);
        assembler = mock(AgentContextAssembler.class);
        snapshots = mock(AgentModelSnapshotService.class);
        tools = mock(PaperReadToolRegistry.class);
        messages = mock(ResearchMessageMapper.class);
        gateway = new FakeGateway();
        actionResolver = mock(PaperActionResolver.class);
        ticketService = mock(ActionTicketService.class);
        service = new AgentLoopService(runtime, assembler, snapshots, gateway, tools,
                new GroundEvidenceService(), messages, new ObjectMapper(),
                actionResolver, ticketService, null, null);

        turn = new AgentTurnRecord();
        turn.setId(11L); turn.setTurnId("turn-1"); turn.setSessionId(7L); turn.setStatus("QUEUED");
        run = new AgentRunRecord();
        run.setId(21L); run.setRunId("run-1"); run.setTurnId(11L); run.setStatus("RUNNING"); run.setVersion(0);
        when(runtime.createTurn(eq(7L), anyString(), anyString())).thenReturn(turn);
        when(runtime.startRun(anyString(), any(), any(), anyString(), anyString(), ArgumentMatchers.nullable(String.class), ArgumentMatchers.nullable(String.class))).thenReturn(run);
        when(runtime.getRun("run-1")).thenReturn(run);
        when(runtime.transitionRun(eq("run-1"), any(), ArgumentMatchers.nullable(String.class),
                ArgumentMatchers.nullable(String.class), ArgumentMatchers.nullable(String.class))).thenReturn(run);
        when(snapshots.current()).thenReturn(new AgentModelSnapshot("v1", "a".repeat(64), "{\"model\":\"fake\"}"));
        when(tools.definitions(ArgumentMatchers.nullable(String.class))).thenReturn(List.of(
                new AgentToolDefinition("retrieve_paper_evidence", "retrieve", "{\"type\":\"object\"}")));
        when(runtime.registerToolCall(anyString(), anyString(), anyString(), anyBoolean(), anyString()))
                .thenAnswer(invocation -> toolCall(invocation.getArgument(1)));
        when(runtime.transitionToolCall(anyString(), any(), ArgumentMatchers.nullable(String.class),
                ArgumentMatchers.nullable(String.class), ArgumentMatchers.nullable(String.class)))
                .thenAnswer(invocation -> {
                    AgentToolCallRecord call = toolCall("transitioned");
                    call.setToolCallId(invocation.getArgument(0));
                    call.setStatus(invocation.getArgument(1).toString());
                    call.setVersion(1);
                    return call;
                });
        doAnswer(invocation -> {
            com.research.assistant.entity.ResearchMessage message = invocation.getArgument(0);
            if (message.getId() == null) message.setId(100L + System.nanoTime() % 1000);
            return 1;
        }).when(messages).insert(any(com.research.assistant.entity.ResearchMessage.class));
    }

    @Test
    void ordinaryChatCompletesWithoutEvidenceGate() {
        when(assembler.assemble(any())).thenReturn(context(null));
        gateway.add(new ScriptedDecision("你好，我可以帮你阅读论文。", List.of()));

        AgentTurnResult result = service.execute(input("你好"));

        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(result.message()).contains("阅读论文");
        assertThat(result.citations()).isEmpty();
        verify(runtime).transitionRun(eq("run-1"), eq(AgentRunStatus.COMPLETED), anyString(),
                ArgumentMatchers.isNull(), ArgumentMatchers.isNull());
    }

    @Test
    void transientCapabilityProbeFailureDoesNotBlockDirectChat() {
        AiCapabilityService capability = mock(AiCapabilityService.class);
        doThrow(new IllegalStateException("AGENT_MODEL_CAPABILITY_NOT_VERIFIED"))
                .when(capability).requireReady();
        service = new AgentLoopService(runtime, assembler, snapshots, gateway, tools,
                new GroundEvidenceService(), messages, new ObjectMapper(), actionResolver,
                ticketService, capability, null);
        when(assembler.assemble(any())).thenReturn(context(null));
        gateway.add(new ScriptedDecision("可以直接回答。", List.of()));

        AgentTurnResult result = service.execute(input("冒泡排序的复杂度是多少？"));

        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(result.message()).isEqualTo("可以直接回答。");
    }

    @Test
    void selectedContextMayStillReceiveADirectGeneralAnswerWithoutCallingPaperTools() {
        when(assembler.assemble(any())).thenReturn(context(catalog(), Set.of("src-1")));
        gateway.add(new ScriptedDecision("这个问题与当前选区无关，冒泡排序平均复杂度为 $O(n^2)$。", List.of()));

        AgentTurnResult result = service.execute(input("冒泡排序的平均复杂度是多少？"));

        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(result.message()).contains("$O(n^2)$");
        assertThat(result.citations()).isEmpty();
    }

    @Test
    void paperDependentPlainTextCompletesWithoutAForcedEvidenceGate() {
        when(assembler.assemble(any())).thenReturn(context(catalog()));
        gateway.add(new ScriptedDecision("式（21）说明了一个结论。", List.of()));

        AgentTurnResult result = service.execute(input("论文中的式（21）说明了什么？"));

        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(result.message()).isEqualTo("式（21）说明了一个结论。");
        assertThat(result.citations()).isEmpty();
    }

    @Test
    void paperAnswerRequiresReadSourceAndGroundsExactQuote() throws Exception {
        PaperSourceCatalog catalog = catalog();
        when(assembler.assemble(any())).thenReturn(context(catalog));
        gateway.add(decisionTool("m1", "retrieve_paper_evidence", "{\"searches\":[{\"query\":\"accuracy\"}]}"));
        gateway.add(decisionTool("m2", "submit_answer", """
                {"groundingMode":"PAPER","answerBlocks":[
                {"text":"该方法达到 95% accuracy。","sourceObjectIds":["src-1"]}]}
                """));
        when(tools.execute(eq(catalog), eq("retrieve_paper_evidence"), anyString()))
                .thenReturn(new AgentToolExecution("{\"sources\":[{\"sourceObjectId\":\"src-1\"}]}", Set.of("src-1")));

        AgentTurnResult result = service.execute(input("论文准确率是多少？"));

        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(result.citations()).hasSize(1);
        assertThat(result.citations().get(0).citationNumber()).isEqualTo(1);
        assertThat(result.evidence().get(0).sourceObjectId()).isEqualTo("src-1");
        assertThat(result.evidence().get(0).formulaNumber()).isEqualTo("21");
    }

    @Test
    void paperAnswerRemovesModelAuthoredCitationNumbersBeforePersistence() throws Exception {
        PaperSourceCatalog catalog = catalog();
        when(assembler.assemble(any())).thenReturn(context(catalog));
        gateway.add(decisionTool("m1", "retrieve_paper_evidence", "{\"searches\":[{\"query\":\"accuracy\"}]}"));
        gateway.add(decisionTool("m2", "submit_answer", """
                {"groundingMode":"PAPER","answerBlocks":[
                {"text":"该方法达到 95% accuracy [1]。","sourceObjectIds":["src-1"]}]}
                """));
        when(tools.execute(eq(catalog), eq("retrieve_paper_evidence"), anyString()))
                .thenReturn(new AgentToolExecution("{\"sources\":[{\"sourceObjectId\":\"src-1\"}]}", Set.of("src-1")));

        AgentTurnResult result = service.execute(input("论文准确率是多少？"));

        assertThat(result.message()).isEqualTo("该方法达到 95% accuracy。");
        assertThat(result.citations()).singleElement().satisfies(binding ->
                assertThat(result.message().substring(binding.answerStart(), binding.answerEnd()))
                        .isEqualTo("该方法达到 95% accuracy。"));
    }

    @Test
    void citationSanitizerPreservesMathIntervalsAndLatex() {
        String value = "约束为 $t \\in [0,1]$，并满足 $$\\sum_{k=1}^{K} C_k \\le R_c$$。结论成立 [1]。";

        assertThat(AgentLoopService.stripModelCitationMarkers(value))
                .isEqualTo("约束为 $t \\in [0,1]$，并满足 $$\\sum_{k=1}^{K} C_k \\le R_c$$。结论成立。");
    }

    @Test
    void paperCitationBindsTheWholeAnswerBlockWithoutClaimMatching() throws Exception {
        PaperSourceCatalog catalog = catalog();
        when(assembler.assemble(any())).thenReturn(context(catalog));
        gateway.add(decisionTool("m1", "retrieve_paper_evidence", "{\"searches\":[{\"query\":\"accuracy\"}]}"));
        gateway.add(decisionTool("m2", "submit_answer", """
                {"groundingMode":"PAPER","answerBlocks":[
                {"text":"核心结论是**该方法达到 95% accuracy**。","sourceObjectIds":["src-1"]}]}
                """));
        when(tools.execute(eq(catalog), eq("retrieve_paper_evidence"), anyString()))
                .thenReturn(new AgentToolExecution("{\"sources\":[{\"sourceObjectId\":\"src-1\"}]}", Set.of("src-1")));

        AgentTurnResult result = service.execute(input("论文准确率是多少？"));

        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(result.citations()).singleElement().satisfies(binding ->
                assertThat(result.message().substring(binding.answerStart(), binding.answerEnd()))
                        .isEqualTo("核心结论是**该方法达到 95% accuracy**。"));
    }

    @Test
    void paperCitationCanBindAClaimThatSummarizesTwoAdjacentSentences() throws Exception {
        PaperSourceCatalog catalog = catalog();
        when(assembler.assemble(any())).thenReturn(context(catalog));
        gateway.add(decisionTool("m1", "retrieve_paper_evidence", "{\"searches\":[{\"query\":\"accuracy\"}]}"));
        gateway.add(decisionTool("m2", "submit_answer", """
                {"groundingMode":"PAPER","answerBlocks":[
                {"text":"核心结论是该方法的准确率下界。该下界达到 95% accuracy。","sourceObjectIds":["src-1"]}]}
                """));
        when(tools.execute(eq(catalog), eq("retrieve_paper_evidence"), anyString()))
                .thenReturn(new AgentToolExecution("{\"sources\":[{\"sourceObjectId\":\"src-1\"}]}", Set.of("src-1")));

        AgentTurnResult result = service.execute(input("论文准确率是多少？"));

        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(result.citations()).singleElement().satisfies(binding ->
                assertThat(result.message().substring(binding.answerStart(), binding.answerEnd()))
                .isEqualTo(result.message()));
    }

    @Test
    void answerMayMixUncitedGeneralExplanationWithCitedPaperClaim() throws Exception {
        PaperSourceCatalog catalog = catalog();
        when(assembler.assemble(any())).thenReturn(context(catalog));
        gateway.add(decisionTool("m1", "retrieve_paper_evidence", "{\"searches\":[{\"query\":\"accuracy\"}]}"));
        gateway.add(decisionTool("m2", "submit_answer", """
                {"answerBlocks":[
                  {"text":"一般而言，准确率越高通常越好。","sourceObjectIds":[]},
                  {"text":"本文报告达到 95% accuracy。","sourceObjectIds":["src-1"]}]}
                """));
        when(tools.execute(eq(catalog), eq("retrieve_paper_evidence"), anyString()))
                .thenReturn(new AgentToolExecution("{\"sources\":[{\"sourceObjectId\":\"src-1\"}]}", Set.of("src-1")));

        AgentTurnResult result = service.execute(input("论文准确率是多少？"));

        assertThat(result.message()).isEqualTo("一般而言，准确率越高通常越好。\n\n本文报告达到 95% accuracy。");
        assertThat(result.citations()).singleElement().satisfies(binding -> {
            assertThat(binding.sourceObjectId()).isEqualTo("src-1");
            assertThat(result.message().substring(binding.answerStart(), binding.answerEnd()))
                    .isEqualTo("本文报告达到 95% accuracy。");
        });
    }

    @Test
    void paperContextExposesPageActionWithAnExplicitUseInstruction() {
        when(assembler.assemble(any())).thenReturn(context(catalog()));
        gateway.add(decisionTool("m1", "submit_answer",
                "{\"groundingMode\":\"GENERAL_KNOWLEDGE\",\"answerBlocks\":[{\"text\":\"可以概括。\",\"sourceObjectIds\":[]}]}"));

        service.execute(input("找出文章最重要的一条公式结论"));

        assertThat(gateway.requests.get(0).skills()).flatExtracting(AgentSkillBinding::tools)
                .extracting(AgentToolDefinition::name)
                .contains("paper_action");
    }

    @Test
    void modelCanRequestAValidatedPageActionForASelectedSource() {
        PaperSourceCatalog catalog = catalog();
        when(assembler.assemble(any())).thenReturn(context(catalog, Set.of("src-1")));
        ActionTarget target = new PaperActionResolver().resolve(catalog, "src-1");
        when(actionResolver.resolve(catalog, "src-1")).thenReturn(target);
        when(ticketService.issue(eq("run-1"), any(), eq(PaperActionType.HIGHLIGHT), eq(target),
                ArgumentMatchers.isNull(), eq("#ffee58")))
                .thenReturn(new ActionTicketService.IssuedActionTicket("ticket", java.time.Instant.now().plusSeconds(60), null));
        gateway.add(decisionTool("m1", "paper_action", """
                {"actionType":"HIGHLIGHT","sourceObjectId":"src-1","color":"#ffee58"}
                """));

        AgentTurnResult result = service.execute(input("把当前选区高亮"));

        assertThat(result.status()).isEqualTo("WAITING_CLIENT");
        assertThat(result.pendingActions()).singleElement().satisfies(action -> {
            assertThat(action.actionType()).isEqualTo(PaperActionType.HIGHLIGHT);
            assertThat(action.target()).isEqualTo(target);
        });
        verify(runtime).registerToolCall(eq("run-1"), eq("paper_action"), anyString(), eq(false), anyString());
        verify(runtime).transitionRun(eq("run-1"), eq(AgentRunStatus.WAITING_CLIENT),
                anyString(), ArgumentMatchers.isNull(), ArgumentMatchers.isNull());
    }

    @Test
    void ambiguityBecomesNaturalLanguageClarification() {
        when(assembler.assemble(any())).thenReturn(context(catalog()));
        gateway.add(decisionTool("m1", "ask_clarification", "{\"question\":\"你希望处理哪一个公式？\"}"));

        AgentTurnResult result = service.execute(input("把它处理一下"));

        assertThat(result.status()).isEqualTo("WAITING_USER");
        assertThat(result.message()).isEqualTo("你希望处理哪一个公式？");
        verify(runtime).transitionRun(eq("run-1"), eq(AgentRunStatus.WAITING_USER), anyString(),
                ArgumentMatchers.isNull(), ArgumentMatchers.isNull());
    }

    @Test
    void clarificationKeepsTheTerminalAnswerAndClarificationTools() {
        when(assembler.assemble(any())).thenReturn(context(null));
        gateway.add(decisionTool("m1", "submit_answer", "{\"clarification\":\"你希望比较哪两篇论文？\"}"));

        AgentTurnResult result = service.execute(input("帮我比较一下"));

        assertThat(result.status()).isEqualTo("WAITING_USER");
        assertThat(result.message()).isEqualTo("你希望比较哪两篇论文？");
        assertThat(gateway.requests.get(0).tools()).extracting(AgentToolDefinition::name)
                .containsExactly("submit_answer", "ask_clarification");
    }

    @Test
    void paperReadFailureIsReturnedAsNonBlockingUnavailableContext() {
        PaperSourceCatalog catalog = catalog();
        when(assembler.assemble(any())).thenReturn(context(catalog));
        gateway.add(decisionTool("m1", "retrieve_paper_evidence", "{\"searches\":[{\"query\":\"missing\"}]}"));
        gateway.add(new ScriptedDecision("根据论文画像，当前仍可概括其主要思路。", List.of()));
        when(tools.execute(eq(catalog), eq("retrieve_paper_evidence"), anyString())).thenThrow(new IllegalArgumentException("source not found"));

        AgentTurnResult result = service.execute(input("给出结论"));

        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(result.message()).contains("主要思路");
        assertThat(result.citations()).isEmpty();
        assertThat(gateway.requests).hasSize(2);
        assertThat(gateway.requests.get(1).messages()).anyMatch(entry ->
                entry.role() == AgentChatEntry.Role.TOOL && entry.content().contains("\"status\":\"unavailable\""));
    }

    @Test
    void plainMarkdownAfterSuccessfulEvidenceReadFailsTheGroundingContract() {
        PaperSourceCatalog catalog = catalog();
        when(assembler.assemble(any())).thenReturn(context(catalog));
        gateway.add(decisionTool("m1", "retrieve_paper_evidence", "{\"searches\":[{\"query\":\"accuracy\"}]}"));
        gateway.add(new ScriptedDecision("论文报告的方法准确率为 95%。", List.of()));
        when(tools.execute(eq(catalog), eq("retrieve_paper_evidence"), anyString()))
                .thenReturn(new AgentToolExecution("{\"sources\":[{\"sourceObjectId\":\"src-1\"}]}", Set.of("src-1")));

        assertThatThrownBy(() -> service.execute(input("论文准确率是多少？")))
                .hasMessageContaining("GROUNDING_SUBMISSION_REQUIRED");
        verify(runtime).transitionRun(eq("run-1"), eq(AgentRunStatus.FAILED),
                ArgumentMatchers.isNull(), eq("EVIDENCE_SUBMISSION_REQUIRED"), anyString());
    }

    @Test
    void repeatedChangedEvidenceRequestReportsNoNewSourcesAndExhaustion() {
        PaperSourceCatalog catalog = catalog();
        when(assembler.assemble(any())).thenReturn(context(catalog));
        gateway.add(decisionTool("m1", "retrieve_paper_evidence",
                "{\"searches\":[{\"query\":\"accuracy\"}]}"));
        gateway.add(decisionTool("m2", "retrieve_paper_evidence",
                "{\"searches\":[{\"query\":\"performance result\"}]}"));
        gateway.add(decisionTool("m3", "submit_answer",
                "{\"groundingMode\":\"PAPER\",\"answerBlocks\":[{\"text\":\"结论\",\"sourceObjectIds\":[\"src-1\"]}]}"));
        when(tools.execute(eq(catalog), eq("retrieve_paper_evidence"), anyString()))
                .thenReturn(new AgentToolExecution("{\"sources\":[{\"sourceObjectId\":\"src-1\"}]}", Set.of("src-1")));

        AgentTurnResult result = service.execute(input("论文准确率是多少？"));

        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(gateway.requests.get(2).messages()).anyMatch(entry ->
                entry.role() == AgentChatEntry.Role.TOOL
                        && entry.content().contains("\"newSourceCount\":0")
                        && entry.content().contains("\"exhausted\":true"));
    }

    @Test
    void identicalEvidenceRequestsReuseOnlyTheExactRequest() {
        PaperSourceCatalog catalog = catalog();
        when(assembler.assemble(any())).thenReturn(context(catalog));
        String request = "{\"searches\":[{\"query\":\"accuracy\"}]}";
        gateway.add(decisionTool("m1", "retrieve_paper_evidence", request));
        gateway.add(decisionTool("m2", "retrieve_paper_evidence", request));
        gateway.add(decisionTool("m3", "submit_answer", "{\"groundingMode\":\"PAPER\",\"answerBlocks\":[{\"text\":\"结论\",\"sourceObjectIds\":[\"src-1\"]}]}"));
        when(tools.execute(eq(catalog), eq("retrieve_paper_evidence"), anyString()))
                .thenReturn(new AgentToolExecution("{\"sources\":[{\"sourceObjectId\":\"src-1\"}]}", Set.of("src-1")));

        AgentTurnResult result = service.execute(input("论文准确率是多少？"));

        assertThat(result.status()).isEqualTo("COMPLETED");
        verify(tools, times(1)).execute(eq(catalog), eq("retrieve_paper_evidence"), anyString());
        assertThat(gateway.requests.get(2).messages()).anyMatch(entry ->
                entry.role() == AgentChatEntry.Role.TOOL && entry.content().contains("src-1"));
    }

    @Test
    void changedEvidenceRequestRemainsAvailableToTheAgent() {
        PaperSourceCatalog catalog = catalog();
        when(assembler.assemble(any())).thenReturn(context(catalog));
        gateway.add(decisionTool("m1", "retrieve_paper_evidence",
                "{\"searches\":[{\"query\":\"accuracy\"}]}"));
        gateway.add(decisionTool("m2", "retrieve_paper_evidence",
                "{\"searches\":[{\"query\":\"dataset\"}]}"));
        gateway.add(decisionTool("m3", "submit_answer", "{\"groundingMode\":\"PAPER\",\"answerBlocks\":[{\"text\":\"结论\",\"sourceObjectIds\":[\"src-1\"]}]}"));
        when(tools.execute(eq(catalog), eq("retrieve_paper_evidence"), anyString()))
                .thenReturn(new AgentToolExecution("{\"sources\":[{\"sourceObjectId\":\"src-1\"}]}", Set.of("src-1")));

        AgentTurnResult result = service.execute(input("论文准确率和数据集是什么？"));

        assertThat(result.status()).isEqualTo("COMPLETED");
        verify(tools, times(2)).execute(eq(catalog), eq("retrieve_paper_evidence"), anyString());
        assertThat(gateway.requests.get(2).messages()).anyMatch(entry ->
                entry.role() == AgentChatEntry.Role.TOOL && entry.content().contains("src-1"));
    }

    @Test
    void modelTimeoutFailsRunWithoutPersistingAFalseAnswer() {
        when(assembler.assemble(any())).thenReturn(context(null));
        gateway.failure = new RuntimeException("timeout");

        assertThatThrownBy(() -> service.execute(input("hello"))).hasMessageContaining("timeout");
        verify(runtime).transitionRun("run-1", AgentRunStatus.FAILED, null, "MODEL_TIMEOUT", "timeout");
    }

    @Test
    void lateAnswerAfterTimeoutDoesNotPersistAssistantMessage() {
        when(assembler.assemble(any())).thenReturn(context(null));
        when(runtime.getTurnForRun("run-1")).thenReturn(turn);
        doAnswer(invocation -> {
            AgentRunStatus target = invocation.getArgument(1);
            if (target == AgentRunStatus.COMPLETED) {
                run.setStatus(AgentRunStatus.FAILED.name());
                run.setErrorCode("RUN_TIMEOUT");
                throw new IllegalStateException("invalid AgentRun transition: FAILED -> COMPLETED");
            }
            return run;
        }).when(runtime).transitionRun(eq("run-1"), any(), ArgumentMatchers.nullable(String.class),
                ArgumentMatchers.nullable(String.class), ArgumentMatchers.nullable(String.class));
        gateway.add(new ScriptedDecision("迟到的回答不应被写入", List.of()));

        AgentTurnResult result = service.execute(input("hello"));

        assertThat(result.status()).isEqualTo(AgentRunStatus.FAILED.name());
        assertThat(result.message()).contains("超时");
        verify(messages, times(1)).insert(any(com.research.assistant.entity.ResearchMessage.class));
        assertThat(result.message()).doesNotContain("迟到的回答不应被写入");
    }

    @Test
    void duplicateClientRequestReturnsStoredResultWithoutCallingModelAgain() throws Exception {
        when(assembler.assemble(any())).thenReturn(context(null));
        com.research.assistant.entity.ResearchMessage existing = new com.research.assistant.entity.ResearchMessage();
        existing.setRunId("run-1");
        when(messages.selectByMessageKey(7L, "agent-user-request-1")).thenReturn(existing);
        String stored = new ObjectMapper().writeValueAsString(
                new AgentTurnResult("turn-1", "run-1", "COMPLETED", "stored", List.of(), List.of()));
        run.setStatus("COMPLETED"); run.setResultJson(stored);
        when(runtime.getTurnForRun("run-1")).thenReturn(turn);

        AgentTurnResult result = service.execute(input("duplicate"));

        assertThat(result.message()).isEqualTo("stored");
        assertThat(gateway.requests).isEmpty();
    }

    @Test
    void resumesTheSameWaitingRunAfterClarification() {
        when(assembler.assemble(any())).thenReturn(context(null));
        run.setStatus("WAITING_USER");
        when(runtime.getRun("run-1")).thenReturn(run);
        when(runtime.getTurnForRun("run-1")).thenReturn(turn);
        AgentRunRecord resumed = new AgentRunRecord();
        resumed.setId(21L); resumed.setRunId("run-1"); resumed.setTurnId(11L); resumed.setStatus("RUNNING"); resumed.setVersion(1);
        when(runtime.transitionRun("run-1", AgentRunStatus.RUNNING, null, null, null)).thenReturn(resumed);
        gateway.add(new ScriptedDecision("confirmed", List.of()));
        AgentTurnInput resume = new AgentTurnInput(7L, null, "第一个", null, null, List.of(), List.of(),
                null, "resume-request", "run-1");

        AgentTurnResult result = service.execute(resume);

        assertThat(result.turnId()).isEqualTo("turn-1");
        assertThat(result.status()).isEqualTo("COMPLETED");
        verify(runtime).transitionRun("run-1", AgentRunStatus.RUNNING, null, null, null);
    }

    @Test
    void trustedExplicitActionBypassesModelButStillWaitsForClientReceipt() {
        PaperSourceCatalog catalog = catalog();
        when(assembler.assemble(any())).thenReturn(context(catalog));
        ActionTarget target = new PaperActionResolver().resolve(catalog, "src-1");
        when(actionResolver.resolve(catalog, "src-1")).thenReturn(target);
        when(ticketService.issue(eq("run-1"), any(), eq(PaperActionType.HIGHLIGHT), eq(target),
                ArgumentMatchers.isNull(), eq("#ffee58")))
                .thenReturn(new ActionTicketService.IssuedActionTicket("ticket", java.time.Instant.now().plusSeconds(60), null));
        AgentTurnInput explicit = new AgentTurnInput(7L, 9L, null,
                new AgentExplicitAction("HIGHLIGHT", Map.of(
                        "sourceObjectId", "src-1",
                        "color", "#ffee58")),
                null, List.of(), List.of(), null, "explicit-request", null);

        AgentTurnResult result = service.execute(explicit);

        assertThat(result.status()).isEqualTo("WAITING_CLIENT");
        assertThat(result.pendingActions()).singleElement()
                .satisfies(action -> assertThat(action.target()).isEqualTo(target));
        assertThat(gateway.requests).isEmpty();
        verify(runtime).registerToolCall(eq("run-1"), eq("explicit_highlight"), anyString(), eq(false), anyString());
        verify(runtime).transitionRun(eq("run-1"), eq(AgentRunStatus.WAITING_CLIENT),
                anyString(), ArgumentMatchers.isNull(), ArgumentMatchers.isNull());
    }

    private AgentTurnInput input(String text) {
        return new AgentTurnInput(7L, null, text, null, null, List.of(), List.of(), null, "request-1", null);
    }

    private AgentContextSnapshot context(PaperSourceCatalog catalog) {
        return context(catalog, Set.of());
    }

    private AgentContextSnapshot context(PaperSourceCatalog catalog, Set<String> preReadSourceIds) {
        return new AgentContextSnapshot(7L, catalog == null ? null : 9L, catalog,
                List.of(AgentChatEntry.system("system"), AgentChatEntry.user("question")),
                preReadSourceIds, "{}");
    }

    private static ScriptedDecision decisionTool(String id, String name, String args) {
        return new ScriptedDecision("", List.of(new AgentToolRequest(id, name, args)));
    }

    private AgentToolCallRecord toolCall(String name) {
        AgentToolCallRecord call = new AgentToolCallRecord();
        call.setId((long) name.hashCode() & 0xffffL);
        call.setToolCallId("call-" + name + "-" + System.nanoTime());
        call.setRunId("run-1"); call.setToolName(name); call.setStatus("REQUESTED"); call.setVersion(0);
        return call;
    }

    private PaperSourceCatalog catalog() {
        SourceObject source = new SourceObject("src-1", 9, "hash", "parser", 1, SourceContentType.TEXT,
                "The method achieves 95% accuracy on the benchmark.", null, List.of("Results"), "21", Map.of());
        SourceLocator locator = new SourceLocator("loc-1", "src-1", 3, "PDF_NORMALIZED",
                List.of(new NormalizedBoundingBox(.1, .2, .3, .04)), "achieves 95% accuracy", EvidenceLocator.Precision.TEXT_RANGE);
        return new PaperSourceCatalog(9, "hash", "parser", 5, Map.of("src-1", source), Map.of("src-1", List.of(locator)));
    }

    private static final class FakeGateway implements PaperAgentFrameworkExecutor {
        private final Queue<ScriptedDecision> decisions = new ArrayDeque<>();
        private final List<RequestSnapshot> requests = new java.util.ArrayList<>();
        private RuntimeException failure;
        void add(ScriptedDecision decision) { decisions.add(decision); }
        @Override public AgentFrameworkResult execute(List<AgentChatEntry> messages,
                                                      List<AgentToolDefinition> tools,
                                                      ToolHandler handler) {
            return executeScript(messages, tools, List.of(), handler);
        }

        @Override public AgentFrameworkResult execute(List<AgentChatEntry> messages,
                                                       List<AgentToolDefinition> tools,
                                                       List<AgentSkillBinding> skills,
                                                       ToolHandler handler,
                                                       SkillActivationHandler activationHandler,
                                                       ModelCallObserver observer) {
            return executeScript(messages, tools, skills, handler);
        }

        private AgentFrameworkResult executeScript(List<AgentChatEntry> messages,
                                                   List<AgentToolDefinition> tools,
                                                   List<AgentSkillBinding> skills,
                                                   ToolHandler handler) {
            if (failure != null) throw failure;
            int modelCalls = 0;
            int toolCalls = 0;
            List<AgentChatEntry> transcript = new java.util.ArrayList<>(messages);
            while (true) {
                requests.add(new RequestSnapshot(List.copyOf(transcript), tools, skills));
                ScriptedDecision decision = decisions.remove();
                modelCalls++;
                if (decision.toolCalls().isEmpty()) {
                    return new AgentFrameworkResult(decision.text(), modelCalls, toolCalls, 0, 0);
                }
                for (AgentToolRequest call : decision.toolCalls()) {
                    toolCalls++;
                    transcript.add(AgentChatEntry.assistantTool(call.id(), call.name(), call.argumentsJson()));
                    try {
                        AgentToolExecution execution = handler.execute(call);
                        String result = execution.resultJson();
                        if ("submit_answer".equals(call.name()) || "ask_clarification".equals(call.name())
                                || "paper_action".equals(call.name())) {
                            return new AgentFrameworkResult(result, modelCalls, toolCalls, 0, 0);
                        }
                        transcript.add(AgentChatEntry.tool(call.id(), call.name(), result));
                    } catch (RuntimeException error) {
                        transcript.add(AgentChatEntry.tool(call.id(), call.name(),
                                "{\"error\":" + json(error.getMessage()) + "}"));
                    }
                }
            }
        }

        private static String json(String value) {
            try { return new ObjectMapper().writeValueAsString(value); }
            catch (Exception error) { throw new IllegalArgumentException(error); }
        }
    }

    private record ScriptedDecision(String text, List<AgentToolRequest> toolCalls) { }
    private record RequestSnapshot(List<AgentChatEntry> messages, List<AgentToolDefinition> tools,
                                   List<AgentSkillBinding> skills) { }
}
