package com.research.assistant.service.agent.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.dto.agent.AgentExplicitAction;
import com.research.assistant.dto.agent.AgentPendingAction;
import com.research.assistant.dto.agent.AgentTurnInput;
import com.research.assistant.dto.agent.AgentTurnResult;
import com.research.assistant.entity.AgentRunRecord;
import com.research.assistant.entity.AgentToolCallRecord;
import com.research.assistant.entity.AgentTurnRecord;
import com.research.assistant.mapper.ResearchMessageMapper;
import com.research.assistant.service.agent.runtime.AgentModelSnapshot;
import com.research.assistant.service.agent.runtime.AgentRunStatus;
import com.research.assistant.service.agent.runtime.AgentToolCallStatus;
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
import static org.mockito.Mockito.never;
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
        gateway.add(decisionTool("m1", "submit_answer",
                "{\"groundingMode\":\"GENERAL_KNOWLEDGE\",\"answerBlocks\":[{\"text\":\"你好，我可以帮你阅读论文。\",\"sourceObjectIds\":[]}] }"));

        AgentTurnResult result = service.execute(input("你好"));

        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(result.message()).contains("阅读论文");
        assertThat(result.citations()).isEmpty();
        verify(runtime).transitionRun(eq("run-1"), eq(AgentRunStatus.COMPLETED), anyString(),
                ArgumentMatchers.isNull(), ArgumentMatchers.isNull());
    }

    @Test
    void rejectsDecodedControlCharacterAndAcceptsTheCorrectedSubmission() {
        when(assembler.assemble(any())).thenReturn(context(null));
        String malformedArguments = "{\"groundingMode\":\"GENERAL_KNOWLEDGE\",\"answerBlocks\":[{\"text\":\"$"
                + "\\" + "u0005" + "psilon$\",\"sourceObjectIds\":[]}]}";
        String validArguments = "{\"groundingMode\":\"GENERAL_KNOWLEDGE\",\"answerBlocks\":[{\"text\":\"修复后的 $"
                + "\\" + "\\" + "varepsilon$\",\"sourceObjectIds\":[]}]}";
        gateway.add(decisionTool("bad-submit", "submit_answer", malformedArguments));
        gateway.add(decisionTool("good-submit", "submit_answer", validArguments));

        AgentTurnResult result = service.execute(input("解释公式"));

        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(result.message()).contains("修复后的 $\\varepsilon$")
                .doesNotContain(Character.toString((char) 0x0005));
        assertThat(gateway.requests).hasSize(2);
        assertThat(gateway.requests.get(1).messages()).anyMatch(entry ->
                entry.role() == AgentChatEntry.Role.TOOL
                        && entry.content().contains("U+0005")
                        && entry.content().contains("合法 JSON 转义"));
        verify(runtime, times(2)).registerToolCall(eq("run-1"), eq("submit_answer"), anyString(), eq(true), anyString());
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
        gateway.add(decisionTool("m1", "submit_answer",
                "{\"groundingMode\":\"GENERAL_KNOWLEDGE\",\"answerBlocks\":[{\"text\":\"可以直接回答。\",\"sourceObjectIds\":[]}] }"));

        AgentTurnResult result = service.execute(input("冒泡排序的复杂度是多少？"));

        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(result.message()).isEqualTo("可以直接回答。");
    }

    @Test
    void selectedContextMayStillReceiveADirectGeneralAnswerWithoutCallingPaperTools() {
        when(assembler.assemble(any())).thenReturn(context(catalog(), Set.of("src-1")));
        gateway.add(decisionTool("m1", "submit_answer",
                "{\"groundingMode\":\"GENERAL_KNOWLEDGE\",\"answerBlocks\":[{\"text\":\"这个问题与当前选区无关，冒泡排序平均复杂度为 $O(n^2)$。\",\"sourceObjectIds\":[]}] }"));

        AgentTurnResult result = service.execute(input("冒泡排序的平均复杂度是多少？"));

        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(result.message()).contains("$O(n^2)$");
        assertThat(result.citations()).isEmpty();
    }

    @Test
    void paperDependentAnswerMustRetrieveEvidenceAfterUngroundedSubmissionIsRejected() {
        PaperSourceCatalog catalog = catalog();
        when(assembler.assemble(any())).thenReturn(context(catalog));
        gateway.add(decisionTool("m1", "submit_answer",
                "{\"groundingMode\":\"PAPER\",\"answerBlocks\":[{\"text\":\"式（21）说明了一个结论。\",\"sourceObjectIds\":[]}] }"));
        gateway.add(decisionTool("m2", "retrieve_paper_evidence",
                "{\"needs\":[{\"id\":\"formula-meaning\",\"objective\":\"确认式（21）的含义\",\"query\":\"Equation (21)\"}]}"));
        gateway.add(decisionTool("m3", "submit_answer",
                "{\"groundingMode\":\"PAPER\",\"answerBlocks\":[{\"text\":\"式（21）给出准确率结论。\",\"sourceObjectIds\":[\"src-1\"]}] }"));
        when(tools.execute(eq(catalog), eq("retrieve_paper_evidence"), anyString()))
                .thenReturn(new AgentToolExecution(
                        "{\"sources\":[{\"sourceObjectId\":\"src-1\"}],"
                                + "\"evidenceNeeds\":[{\"needId\":\"formula-meaning\","
                                + "\"sourceObjectIds\":[\"src-1\"]}]}",
                        Set.of("src-1")));

        AgentTurnResult result = service.execute(input("论文中的式（21）说明了什么？"));

        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(result.citations()).hasSize(1);
        assertThat(gateway.requests).hasSize(3);
        assertThat(gateway.requests.get(1).messages()).anyMatch(entry ->
                entry.role() == AgentChatEntry.Role.TOOL
                        && entry.content().contains("每个答案块都必须绑定已读取的论文来源"));
    }

    @Test
    void paperAnswerRequiresReadSourceAndGroundsExactQuote() throws Exception {
        PaperSourceCatalog catalog = catalog();
        when(assembler.assemble(any())).thenReturn(context(catalog));
        gateway.add(decisionTool("m1", "retrieve_paper_evidence", "{\"needs\":[{\"id\":\"accuracy\",\"query\":\"accuracy\"}]}"));
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
    void displayFormulaRequiresFormulaEvidenceThatWasActuallyReadable() {
        PaperSourceCatalog catalog = formulaCatalog();
        when(assembler.assemble(any())).thenReturn(context(catalog));
        gateway.add(decisionTool("m1", "retrieve_paper_evidence",
                "{\"needs\":[{\"id\":\"formula\",\"objective\":\"确认公式\",\"query\":\"Equation 12\"}]}"));
        gateway.add(decisionTool("m2", "submit_answer", """
                {"groundingMode":"PAPER","answerBlocks":[
                {"text":"$$T=R(1-\\\\varepsilon)$$","sourceObjectIds":["src-formula"]}]}
                """));
        gateway.add(decisionTool("m3", "submit_answer", """
                {"groundingMode":"PAPER","answerBlocks":[
                {"text":"公式（12）用于定义有效吞吐量。","sourceObjectIds":["src-formula"]}]}
                """));
        when(tools.execute(eq(catalog), eq("retrieve_paper_evidence"), anyString()))
                .thenReturn(new AgentToolExecution("{\"sources\":[{\"sourceObjectId\":\"src-formula\"}]}",
                        Set.of("src-formula")));

        AgentTurnResult result = service.execute(input("公式（12）是什么？"));

        assertThat(result.message()).isEqualTo("公式（12）用于定义有效吞吐量。");
        assertThat(gateway.requests.get(2).messages()).anyMatch(entry ->
                entry.role() == AgentChatEntry.Role.TOOL
                        && entry.content().contains("必须绑定可靠公式文本"));
    }

    @Test
    void visuallyReadFormulaMaySupportAnExactDisplayExpression() {
        PaperSourceCatalog catalog = formulaAndTextCatalog();
        when(assembler.assemble(any())).thenReturn(context(catalog));
        gateway.add(decisionTool("m1", "retrieve_paper_evidence",
                "{\"needs\":[{\"id\":\"formula\",\"objective\":\"确认公式\",\"query\":\"Equation 12\"}]}"));
        gateway.add(decisionTool("m2", "submit_answer", """
                {"groundingMode":"PAPER","answerBlocks":[
                {"text":"$$T=R(1-\\\\varepsilon)$$","sourceObjectIds":["src-1"]}]}
                """));
        gateway.add(decisionTool("m3", "submit_answer", """
                {"groundingMode":"PAPER","answerBlocks":[
                {"text":"$$T=R(1-\\\\varepsilon)$$","sourceObjectIds":["src-formula"]}]}
                """));
        AgentVisualContent visual = new AgentVisualContent(
                "src-formula", 3, "FORMULA", "image/png", new byte[]{1}, 200, 80);
        when(tools.execute(eq(catalog), eq("retrieve_paper_evidence"), anyString()))
                .thenReturn(new AgentToolExecution("{\"sources\":[{\"sourceObjectId\":\"src-formula\"},{\"sourceObjectId\":\"src-1\"}]}",
                        Set.of("src-formula", "src-1"), List.of(visual)));

        AgentTurnResult result = service.execute(input("公式（12）是什么？"));

        assertThat(result.message()).isEqualTo("$$T=R(1-\\varepsilon)$$");
        assertThat(result.evidence()).singleElement()
                .satisfies(view -> assertThat(view.contentType()).isEqualTo("FORMULA"));
        assertThat(gateway.requests.get(2).messages()).anyMatch(entry ->
                entry.role() == AgentChatEntry.Role.TOOL
                        && entry.content().contains("src-formula")
                        && entry.content().contains("不要重新检索"));
    }

    @Test
    void aRetrievedFigureVisualMustBeBoundIntoTheFinalPaperAnswer() {
        PaperSourceCatalog catalog = figureCatalog();
        when(assembler.assemble(any())).thenReturn(context(catalog));
        gateway.add(decisionTool("m1", "retrieve_paper_evidence",
                "{\"needs\":[{\"id\":\"figure\",\"objective\":\"确认图中趋势\","
                        + "\"query\":\"Figure 2\",\"contentTypes\":[\"FIGURE\"]}]}"));
        gateway.add(decisionTool("m2", "submit_answer", """
                {"groundingMode":"PAPER","answerBlocks":[
                {"text":"图 2 展示频谱效率。","sourceObjectIds":["src-1"]}]}
                """));
        gateway.add(decisionTool("m3", "submit_answer", """
                {"groundingMode":"PAPER","answerBlocks":[
                {"text":"图 2 展示频谱效率。","sourceObjectIds":["src-figure","src-1"]}]}
                """));
        AgentVisualContent visual = new AgentVisualContent(
                "src-figure", 3, "FIGURE", "image/png", new byte[]{1}, 320, 180);
        when(tools.execute(eq(catalog), eq("retrieve_paper_evidence"), anyString()))
                .thenReturn(new AgentToolExecution("{\"sources\":[]}",
                        Set.of("src-figure", "src-1"), List.of(visual)));

        AgentTurnResult result = service.execute(input("图 2 展示了什么？"));

        assertThat(result.evidence()).extracting(view -> view.contentType())
                .contains("FIGURE", "TEXT");
        assertThat(gateway.requests.get(2).messages()).anyMatch(entry ->
                entry.role() == AgentChatEntry.Role.TOOL
                        && entry.content().contains("FIGURE 视觉来源"));
    }

    @Test
    void paperAnswerRemovesModelAuthoredCitationNumbersBeforePersistence() throws Exception {
        PaperSourceCatalog catalog = catalog();
        when(assembler.assemble(any())).thenReturn(context(catalog));
        gateway.add(decisionTool("m1", "retrieve_paper_evidence", "{\"needs\":[{\"id\":\"accuracy\",\"query\":\"accuracy\"}]}"));
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
        gateway.add(decisionTool("m1", "retrieve_paper_evidence", "{\"needs\":[{\"id\":\"accuracy\",\"query\":\"accuracy\"}]}"));
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
        gateway.add(decisionTool("m1", "retrieve_paper_evidence", "{\"needs\":[{\"id\":\"accuracy\",\"query\":\"accuracy\"}]}"));
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
        gateway.add(decisionTool("m1", "retrieve_paper_evidence", "{\"needs\":[{\"id\":\"accuracy\",\"query\":\"accuracy\"}]}"));
        gateway.add(decisionTool("m2", "submit_answer", """
                {"groundingMode":"MIXED","answerBlocks":[
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
        verify(runtime, never()).transitionToolCall(anyString(), eq(AgentToolCallStatus.WAITING_CLIENT),
                anyString(), ArgumentMatchers.isNull(), ArgumentMatchers.isNull());
    }

    @Test
    void modelCanBatchOneHighlightAcrossExplicitSourcesWithIndependentTickets() {
        PaperSourceCatalog base = catalog();
        SourceObject second = new SourceObject("src-2", 9, "hash", "parser", 1, SourceContentType.TEXT,
                "The baseline reaches 90% accuracy.", null, List.of("Results"), "", Map.of());
        SourceLocator secondLocator = new SourceLocator("loc-2", "src-2", 4, "PDF_NORMALIZED",
                List.of(new NormalizedBoundingBox(.2, .3, .25, .04)), "reaches 90% accuracy",
                EvidenceLocator.Precision.TEXT_RANGE);
        PaperSourceCatalog batchCatalog = new PaperSourceCatalog(9, "hash", "parser", 5,
                Map.of("src-1", base.objects().get("src-1"), "src-2", second),
                Map.of("src-1", base.locators().get("src-1"), "src-2", List.of(secondLocator)));
        when(assembler.assemble(any())).thenReturn(context(batchCatalog, Set.of("src-1", "src-2")));
        ActionTarget firstTarget = new PaperActionResolver().resolve(batchCatalog, "src-1");
        ActionTarget secondTarget = new PaperActionResolver().resolve(batchCatalog, "src-2");
        when(actionResolver.resolve(batchCatalog, "src-1")).thenReturn(firstTarget);
        when(actionResolver.resolve(batchCatalog, "src-2")).thenReturn(secondTarget);
        when(ticketService.issue(eq("run-1"), any(), eq(PaperActionType.HIGHLIGHT), any(ActionTarget.class),
                ArgumentMatchers.isNull(), eq("#ffee58")))
                .thenAnswer(invocation -> new ActionTicketService.IssuedActionTicket(
                        "ticket-" + invocation.getArgument(3, ActionTarget.class).sourceObjectId(),
                        java.time.Instant.now().plusSeconds(60), null));
        gateway.add(decisionTool("m1", "paper_action", """
                {"actionType":"HIGHLIGHT","sourceObjectIds":["src-1","src-2"],"color":"#ffee58"}
                """));

        AgentTurnResult result = service.execute(input("把两个来源都高亮"));

        assertThat(result.status()).isEqualTo("WAITING_CLIENT");
        assertThat(result.pendingActions()).extracting(AgentPendingAction::target)
                .containsExactly(firstTarget, secondTarget);
        verify(ticketService, times(2)).issue(eq("run-1"), any(), eq(PaperActionType.HIGHLIGHT),
                any(ActionTarget.class), ArgumentMatchers.isNull(), eq("#ffee58"));
        verify(runtime, times(2)).registerToolCall(eq("run-1"), eq("paper_action"), anyString(),
                eq(false), anyString());
    }

    @Test
    void modelCanRequestDifferentPageActionsInOneOperationsList() {
        PaperSourceCatalog base = catalog();
        SourceObject second = new SourceObject("src-2", 9, "hash", "parser", 1, SourceContentType.TEXT,
                "The baseline reaches 90% accuracy.", null, List.of("Results"), "", Map.of());
        SourceLocator secondLocator = new SourceLocator("loc-2", "src-2", 4, "PDF_NORMALIZED",
                List.of(new NormalizedBoundingBox(.2, .3, .25, .04)), "reaches 90% accuracy",
                EvidenceLocator.Precision.TEXT_RANGE);
        PaperSourceCatalog batchCatalog = new PaperSourceCatalog(9, "hash", "parser", 5,
                Map.of("src-1", base.objects().get("src-1"), "src-2", second),
                Map.of("src-1", base.locators().get("src-1"), "src-2", List.of(secondLocator)));
        when(assembler.assemble(any())).thenReturn(context(batchCatalog, Set.of("src-1", "src-2")));
        ActionTarget firstTarget = new PaperActionResolver().resolve(batchCatalog, "src-1");
        ActionTarget secondTarget = new PaperActionResolver().resolve(batchCatalog, "src-2");
        when(actionResolver.resolve(batchCatalog, "src-1")).thenReturn(firstTarget);
        when(actionResolver.resolve(batchCatalog, "src-2")).thenReturn(secondTarget);
        when(ticketService.issue(eq("run-1"), any(), any(PaperActionType.class), any(ActionTarget.class),
                ArgumentMatchers.isNull(), eq("#ffee58")))
                .thenAnswer(invocation -> new ActionTicketService.IssuedActionTicket(
                        "ticket-" + invocation.getArgument(3, ActionTarget.class).sourceObjectId(),
                        java.time.Instant.now().plusSeconds(60), null));
        gateway.add(decisionTool("m1", "paper_action", """
                {"operations":[
                  {"actionType":"HIGHLIGHT","sourceObjectId":"src-1","color":"#ffee58"},
                  {"actionType":"UNDERLINE","sourceObjectId":"src-2","color":"#ffee58"}
                ]}
                """));

        AgentTurnResult result = service.execute(input("把第一个来源高亮、第二个来源加下划线"));

        assertThat(result.status()).isEqualTo("WAITING_CLIENT");
        assertThat(result.pendingActions()).extracting(AgentPendingAction::actionType)
                .containsExactly(PaperActionType.HIGHLIGHT, PaperActionType.UNDERLINE);
        assertThat(result.pendingActions()).extracting(AgentPendingAction::target)
                .containsExactly(firstTarget, secondTarget);
        verify(ticketService).issue(eq("run-1"), any(), eq(PaperActionType.HIGHLIGHT), eq(firstTarget),
                ArgumentMatchers.isNull(), eq("#ffee58"));
        verify(ticketService).issue(eq("run-1"), any(), eq(PaperActionType.UNDERLINE), eq(secondTarget),
                ArgumentMatchers.isNull(), eq("#ffee58"));
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
        gateway.add(decisionTool("m1", "submit_answer",
                "{\"groundingMode\":\"GENERAL_KNOWLEDGE\",\"clarification\":\"你希望比较哪两篇论文？\"}"));

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
        gateway.add(decisionTool("m1", "retrieve_paper_evidence", "{\"needs\":[{\"id\":\"missing\",\"query\":\"missing\"}]}"));
        gateway.add(decisionTool("m2", "submit_answer",
                "{\"groundingMode\":\"GENERAL_KNOWLEDGE\",\"answerBlocks\":[{\"text\":\"当前无法确认。\",\"sourceObjectIds\":[]}] }"));
        when(tools.execute(eq(catalog), eq("retrieve_paper_evidence"), anyString())).thenThrow(new IllegalArgumentException("source not found"));

        AgentTurnResult result = service.execute(input("给出结论"));

        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(result.message()).isEqualTo("当前无法确认。");
        assertThat(result.citations()).isEmpty();
        assertThat(gateway.requests).hasSize(2);
        assertThat(gateway.requests.get(1).messages()).anyMatch(entry ->
                entry.role() == AgentChatEntry.Role.TOOL && entry.content().contains("\"status\":\"unavailable\""));
    }

    @Test
    void plainMarkdownAfterSuccessfulEvidenceReadFailsTheGroundingContract() {
        PaperSourceCatalog catalog = catalog();
        when(assembler.assemble(any())).thenReturn(context(catalog));
        gateway.add(decisionTool("m1", "retrieve_paper_evidence", "{\"needs\":[{\"id\":\"accuracy\",\"query\":\"accuracy\"}]}"));
        gateway.add(new ScriptedDecision("论文报告的方法准确率为 95%。", List.of()));
        when(tools.execute(eq(catalog), eq("retrieve_paper_evidence"), anyString()))
                .thenReturn(new AgentToolExecution("{\"sources\":[{\"sourceObjectId\":\"src-1\"}]}", Set.of("src-1")));

        assertThatThrownBy(() -> service.execute(input("论文准确率是多少？")))
                .hasMessageContaining("GROUNDING_SUBMISSION_REQUIRED");
        verify(runtime).transitionRun(eq("run-1"), eq(AgentRunStatus.FAILED),
                ArgumentMatchers.isNull(), eq("EVIDENCE_SUBMISSION_REQUIRED"), anyString());
    }

    @Test
    void repeatedChangedEvidenceRequestReportsNoNewSourcesWithoutClaimingExhaustion() {
        PaperSourceCatalog catalog = catalog();
        when(assembler.assemble(any())).thenReturn(context(catalog));
        gateway.add(decisionTool("m1", "retrieve_paper_evidence",
                "{\"needs\":[{\"id\":\"accuracy\",\"objective\":\"确认准确率\",\"query\":\"accuracy\"}]}"));
        gateway.add(decisionTool("m2", "retrieve_paper_evidence",
                "{\"needs\":[{\"id\":\"accuracy\",\"objective\":\"确认准确率\",\"query\":\"performance result\","
                        + "\"refinementReason\":\"首次来源尚未给出准确率结果\"}]}"));
        gateway.add(decisionTool("m3", "submit_answer",
                "{\"groundingMode\":\"PAPER\",\"answerBlocks\":[{\"text\":\"结论\",\"sourceObjectIds\":[\"src-1\"]}]}"));
        when(tools.execute(eq(catalog), eq("retrieve_paper_evidence"), anyString()))
                .thenReturn(
                        new AgentToolExecution("{\"sources\":[{\"sourceObjectId\":\"src-1\"}],\"evidenceNeeds\":[{\"needId\":\"accuracy\",\"sourceObjectIds\":[\"src-1\"]}]}", Set.of("src-1")),
                        new AgentToolExecution("{\"sources\":[{\"sourceObjectId\":\"src-1\"}],\"evidenceNeeds\":[{\"needId\":\"accuracy\",\"sourceObjectIds\":[\"src-1\"]}]}", Set.of("src-1")));

        AgentTurnResult result = service.execute(input("论文准确率是多少？"));

        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(gateway.requests.get(2).messages()).anyMatch(entry ->
                entry.role() == AgentChatEntry.Role.TOOL
                        && entry.content().contains("\"outcome\":\"same_sources\"")
                        && entry.content().contains("\"recommendedAction\":\"stop\"")
                        && !entry.content().contains("\"exhausted\""));
    }

    @Test
    void identicalEvidenceRequestsReuseOnlyTheExactRequest() {
        PaperSourceCatalog catalog = catalog();
        when(assembler.assemble(any())).thenReturn(context(catalog));
        String request = "{\"needs\":[{\"id\":\"accuracy\",\"objective\":\"确认准确率\",\"query\":\"accuracy\"}]}";
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
    void normalizedEquivalentNeedRequestsAreReportedAsDuplicates() {
        PaperSourceCatalog catalog = catalog();
        when(assembler.assemble(any())).thenReturn(context(catalog));
        gateway.add(decisionTool("m1", "retrieve_paper_evidence", """
                {"needs":[{"id":"accuracy","objective":"确认准确率", "query":"Accuracy   result",
                "keywords":["Beta","Alpha"]}]}
                """));
        gateway.add(decisionTool("m2", "retrieve_paper_evidence", """
                {"needs":[{"keywords":["alpha","BETA"],"query":" accuracy result ",
                "objective":"确认准确率","id":"accuracy"}]}
                """));
        gateway.add(decisionTool("m3", "submit_answer",
                "{\"groundingMode\":\"PAPER\",\"answerBlocks\":[{\"text\":\"结论\",\"sourceObjectIds\":[\"src-1\"]}]}"));
        when(tools.execute(eq(catalog), eq("retrieve_paper_evidence"), anyString()))
                .thenReturn(new AgentToolExecution(
                        "{\"status\":\"found\",\"sources\":[{\"sourceObjectId\":\"src-1\"}],"
                                + "\"evidenceNeeds\":[{\"needId\":\"accuracy\",\"retrievalStatus\":\"found\",\"sourceObjectIds\":[\"src-1\"]}]}",
                        Set.of("src-1")));

        AgentTurnResult result = service.execute(input("论文准确率是多少？"));

        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(gateway.requests).anySatisfy(request -> assertThat(request.messages()).anyMatch(entry ->
                entry.role() == AgentChatEntry.Role.TOOL
                        && entry.content().contains("\"outcome\":\"duplicate_request\"")
                        && entry.content().contains("\"recommendedAction\":\"stop\"")));
    }

    @Test
    void stoppedNeedsDoNotExecuteRetrievalAgain() {
        PaperSourceCatalog catalog = catalog();
        when(assembler.assemble(any())).thenReturn(context(catalog));
        String request = "{\"needs\":[{\"id\":\"accuracy\",\"objective\":\"确认准确率\",\"query\":\"accuracy\"}]}";
        gateway.add(decisionTool("m1", "retrieve_paper_evidence", request));
        gateway.add(decisionTool("m2", "retrieve_paper_evidence", request));
        gateway.add(decisionTool("m3", "retrieve_paper_evidence", request));
        gateway.add(decisionTool("m4", "submit_answer",
                "{\"groundingMode\":\"PAPER\",\"answerBlocks\":[{\"text\":\"结论\",\"sourceObjectIds\":[\"src-1\"]}]}"));
        when(tools.execute(eq(catalog), eq("retrieve_paper_evidence"), anyString()))
                .thenReturn(new AgentToolExecution(
                        "{\"status\":\"found\",\"sources\":[{\"sourceObjectId\":\"src-1\"}],"
                                + "\"evidenceNeeds\":[{\"needId\":\"accuracy\",\"retrievalStatus\":\"found\",\"sourceObjectIds\":[\"src-1\"]}]}",
                        Set.of("src-1")));

        AgentTurnResult result = service.execute(input("论文准确率是多少？"));

        assertThat(result.status()).isEqualTo("COMPLETED");
        verify(tools, times(1)).execute(eq(catalog), eq("retrieve_paper_evidence"), anyString());
        assertThat(gateway.requests.get(3).messages()).anyMatch(entry ->
                entry.role() == AgentChatEntry.Role.TOOL
                        && entry.content().contains("\"status\":\"need_stopped\"")
                        && entry.content().contains("\"recommendedAction\":\"answer\""));
    }

    @Test
    void repeatedStaticValidationFailureStopsTheNeed() {
        PaperSourceCatalog catalog = catalog();
        when(assembler.assemble(any())).thenReturn(context(catalog));
        String request = "{\"needs\":[{\"id\":\"missing\",\"objective\":\"确认目标\"}]}";
        gateway.add(decisionTool("m1", "retrieve_paper_evidence", request));
        gateway.add(decisionTool("m2", "retrieve_paper_evidence", request));
        gateway.add(decisionTool("m3", "submit_answer",
                "{\"groundingMode\":\"GENERAL_KNOWLEDGE\",\"answerBlocks\":[{\"text\":\"当前无法确认。\",\"sourceObjectIds\":[]}]}"));
        when(tools.execute(eq(catalog), eq("retrieve_paper_evidence"), anyString()))
                .thenReturn(new AgentToolExecution(
                        "{\"status\":\"invalid_request\",\"sources\":[],\"evidenceNeeds\":[],"
                                + "\"issues\":[{\"needId\":\"missing\",\"field\":\"query\","
                                + "\"code\":\"MISSING_RETRIEVAL_ANCHOR\",\"message\":\"缺少锚点\"}]}",
                        Set.of()));

        AgentTurnResult result = service.execute(input("论文是否讨论了目标？"));

        assertThat(result.status()).isEqualTo("COMPLETED");
        verify(tools, times(1)).execute(eq(catalog), eq("retrieve_paper_evidence"), anyString());
        assertThat(gateway.requests.get(2).messages()).anyMatch(entry ->
                entry.role() == AgentChatEntry.Role.TOOL
                        && entry.content().contains("\"status\":\"need_stopped\"")
                        && entry.content().contains("连续两次未通过输入契约"));
    }

    @Test
    void invalidContinuationExplainsObjectiveAndRefinementContractWithoutCallingSearchAgain() {
        PaperSourceCatalog catalog = catalog();
        when(assembler.assemble(any())).thenReturn(context(catalog));
        gateway.add(decisionTool("m1", "retrieve_paper_evidence",
                "{\"needs\":[{\"id\":\"accuracy\",\"objective\":\"确认准确率\",\"query\":\"accuracy\"}]}"));
        gateway.add(decisionTool("m2", "retrieve_paper_evidence",
                "{\"needs\":[{\"id\":\"accuracy\",\"objective\":\"确认数据集\",\"query\":\"dataset\"}]}"));
        gateway.add(decisionTool("m3", "retrieve_paper_evidence",
                "{\"needs\":[{\"id\":\"accuracy\",\"objective\":\"确认准确率\",\"query\":\"performance\"}]}"));
        gateway.add(decisionTool("m4", "submit_answer",
                "{\"groundingMode\":\"PAPER\",\"answerBlocks\":[{\"text\":\"结论\",\"sourceObjectIds\":[\"src-1\"]}]}"));
        when(tools.execute(eq(catalog), eq("retrieve_paper_evidence"), anyString()))
                .thenReturn(new AgentToolExecution(
                        "{\"status\":\"found\",\"sources\":[{\"sourceObjectId\":\"src-1\"}],"
                                + "\"evidenceNeeds\":[{\"needId\":\"accuracy\",\"retrievalStatus\":\"found\",\"sourceObjectIds\":[\"src-1\"]}]}",
                        Set.of("src-1")));

        AgentTurnResult result = service.execute(input("论文准确率是多少？"));

        assertThat(result.status()).isEqualTo("COMPLETED");
        verify(tools, times(1)).execute(eq(catalog), eq("retrieve_paper_evidence"), anyString());
        assertThat(gateway.requests).anySatisfy(request -> assertThat(request.messages()).anyMatch(entry ->
                entry.role() == AgentChatEntry.Role.TOOL
                        && entry.content().contains("NEED_OBJECTIVE_CHANGED")));
        assertThat(gateway.requests).anySatisfy(request -> assertThat(request.messages()).anyMatch(entry ->
                entry.role() == AgentChatEntry.Role.TOOL
                        && entry.content().contains("MISSING_REFINEMENT_REASON")));
    }

    @Test
    void newNeedAfterTheInitialPlanGetsOneChanceToReturnToTheOriginalId() {
        PaperSourceCatalog catalog = catalog();
        when(assembler.assemble(any())).thenReturn(context(catalog));
        gateway.add(decisionTool("m1", "retrieve_paper_evidence",
                "{\"needs\":[{\"id\":\"accuracy\",\"objective\":\"确认准确率\",\"query\":\"accuracy\"}]}"));
        gateway.add(decisionTool("m2", "retrieve_paper_evidence",
                "{\"needs\":[{\"id\":\"dataset\",\"objective\":\"确认数据集\",\"query\":\"dataset\"}]}"));
        gateway.add(decisionTool("m3", "retrieve_paper_evidence",
                "{\"needs\":[{\"id\":\"accuracy\",\"objective\":\"确认准确率\","
                        + "\"query\":\"benchmark accuracy\",\"refinementReason\":\"首批缺少基准细节\"}]}"));
        gateway.add(decisionTool("m4", "submit_answer", "{\"groundingMode\":\"PAPER\",\"answerBlocks\":[{\"text\":\"结论\",\"sourceObjectIds\":[\"src-1\"]}]}"));
        when(tools.execute(eq(catalog), eq("retrieve_paper_evidence"), anyString()))
                .thenReturn(new AgentToolExecution(
                        "{\"status\":\"found\",\"sources\":[{\"sourceObjectId\":\"src-1\"}],"
                                + "\"evidenceNeeds\":[{\"needId\":\"accuracy\",\"sourceObjectIds\":[\"src-1\"]}]}",
                        Set.of("src-1")));

        AgentTurnResult result = service.execute(input("论文准确率和数据集是什么？"));

        assertThat(result.status()).isEqualTo("COMPLETED");
        verify(tools, times(2)).execute(eq(catalog), eq("retrieve_paper_evidence"), anyString());
        assertThat(gateway.requests.get(2).messages()).anyMatch(entry ->
                entry.role() == AgentChatEntry.Role.TOOL
                        && entry.content().contains("NEW_NEED_NOT_ALLOWED")
                        && entry.content().contains("\"status\":\"invalid_request\""));
    }

    @Test
    void reportsProgressPerNeedAcrossRefinementRequests() {
        PaperSourceCatalog catalog = catalog();
        when(assembler.assemble(any())).thenReturn(context(catalog));
        gateway.add(decisionTool("m1", "retrieve_paper_evidence",
                "{\"needs\":[{\"id\":\"mechanism\",\"objective\":\"确认论文机制\",\"query\":\"mechanism\"}]}"));
        gateway.add(decisionTool("m2", "retrieve_paper_evidence",
                "{\"needs\":[{\"id\":\"mechanism\",\"objective\":\"确认论文机制\",\"query\":\"mechanism result\","
                        + "\"refinementReason\":\"首批来源缺少机制结果\"}]}"));
        gateway.add(decisionTool("m3", "retrieve_paper_evidence",
                "{\"needs\":[{\"id\":\"mechanism\",\"objective\":\"确认论文机制\",\"query\":\"mechanism conclusion\","
                        + "\"refinementReason\":\"第二批来源仍缺少机制结论\"}]}"));
        gateway.add(decisionTool("m4", "submit_answer",
                "{\"groundingMode\":\"PAPER\",\"answerBlocks\":[{\"text\":\"结论\",\"sourceObjectIds\":[\"src-1\"]}]}"));
        when(tools.execute(eq(catalog), eq("retrieve_paper_evidence"), anyString()))
                .thenReturn(
                        new AgentToolExecution("{\"sources\":[{\"sourceObjectId\":\"src-1\"}],\"evidenceNeeds\":[{\"needId\":\"mechanism\",\"sourceObjectIds\":[\"src-1\"]}]}", Set.of("src-1")),
                        new AgentToolExecution("{\"sources\":[{\"sourceObjectId\":\"src-1\"},{\"sourceObjectId\":\"src-2\"}],\"evidenceNeeds\":[{\"needId\":\"mechanism\",\"sourceObjectIds\":[\"src-1\",\"src-2\"]}]}", Set.of("src-1", "src-2")),
                        new AgentToolExecution("{\"sources\":[{\"sourceObjectId\":\"src-2\"}],\"evidenceNeeds\":[{\"needId\":\"mechanism\",\"sourceObjectIds\":[\"src-2\"]}]}", Set.of("src-2")));

        AgentTurnResult result = service.execute(input("论文中的机制是什么？"));

        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(gateway.requests.get(3).messages()).anyMatch(entry ->
                entry.role() == AgentChatEntry.Role.TOOL
                        && entry.content().contains("\"outcome\":\"same_sources\"")
                        && entry.content().contains("\"recommendedAction\":\"stop\""));
    }

    @Test
    void stopsOneNeedAfterThreeEffectiveEvidenceReads() {
        PaperSourceCatalog catalog = catalog();
        when(assembler.assemble(any())).thenReturn(context(catalog));
        gateway.add(decisionTool("m1", "retrieve_paper_evidence",
                "{\"needs\":[{\"id\":\"formula\",\"objective\":\"确认核心公式\",\"query\":\"formula 1\"}]}"));
        for (int attempt = 2; attempt <= 5; attempt++) {
            gateway.add(decisionTool("m" + attempt, "retrieve_paper_evidence",
                    "{\"needs\":[{\"id\":\"formula\",\"objective\":\"确认核心公式\","
                            + "\"query\":\"formula " + attempt + "\","
                            + "\"refinementReason\":\"上一批未找到可确认的核心公式\"}]}"));
        }
        gateway.add(decisionTool("m6", "submit_answer",
                "{\"groundingMode\":\"PAPER\",\"answerBlocks\":[{\"text\":\"仅确认公式作用。\",\"sourceObjectIds\":[\"src-1\"]}]}"));
        when(tools.execute(eq(catalog), eq("retrieve_paper_evidence"), anyString()))
                .thenReturn(
                        evidenceResult("src-1"), evidenceResult("src-2"),
                        evidenceResult("src-3"), evidenceResult("src-4"));

        AgentTurnResult result = service.execute(input("核心公式是什么？"));

        assertThat(result.status()).isEqualTo("COMPLETED");
        verify(tools, times(3)).execute(eq(catalog), eq("retrieve_paper_evidence"), anyString());
        assertThat(gateway.requests.get(5).messages()).anyMatch(entry ->
                entry.role() == AgentChatEntry.Role.TOOL
                        && entry.content().contains("本轮证据检索已达到三次")
                        && entry.content().contains("need_stopped"));
    }

    @Test
    void mixedBatchReportsIndependentNeedProgressWithTopLevelScope() {
        PaperSourceCatalog catalog = catalog();
        when(assembler.assemble(any())).thenReturn(context(catalog));
        gateway.add(decisionTool("m1", "retrieve_paper_evidence", """
                {"needs":[
                {"id":"mechanism","objective":"确认机制","query":"mechanism"},
                {"id":"result","objective":"确认结果","query":"missing result"}]}
                """));
        gateway.add(decisionTool("m2", "submit_answer",
                "{\"groundingMode\":\"PAPER\",\"answerBlocks\":[{\"text\":\"机制结论\",\"sourceObjectIds\":[\"src-1\"]}]}"));
        when(tools.execute(eq(catalog), eq("retrieve_paper_evidence"), anyString()))
                .thenReturn(new AgentToolExecution(
                        "{\"status\":\"found\",\"sources\":[{\"sourceObjectId\":\"src-1\"}],"
                                + "\"evidenceNeeds\":["
                                + "{\"needId\":\"mechanism\",\"retrievalStatus\":\"found\",\"sourceObjectIds\":[\"src-1\"]},"
                                + "{\"needId\":\"result\",\"retrievalStatus\":\"not_found\",\"sourceObjectIds\":[]}]}",
                        Set.of("src-1")));

        AgentTurnResult result = service.execute(input("论文的机制和结果是什么？"));

        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(gateway.requests.get(1).messages()).anyMatch(entry ->
                entry.role() == AgentChatEntry.Role.TOOL
                        && entry.content().contains("\"outcome\":\"new_sources\"")
                        && entry.content().contains("\"outcome\":\"no_match\"")
                        && entry.content().contains("\"recommendedAction\":\"refine_once\"")
                        && entry.content().contains("\"newDistinctSources\":1")
                        && entry.content().contains("\"noProgress\":false")
                        && entry.content().contains("\"stopRecommended\":false")
                        && entry.content().contains("\"stopScope\":\"individual_need\""));
    }

    @Test
    void reportsFirstNoMatchBeforeStoppingAnUnproductiveRefinement() {
        PaperSourceCatalog catalog = catalog();
        when(assembler.assemble(any())).thenReturn(context(catalog));
        gateway.add(decisionTool("m1", "retrieve_paper_evidence",
                "{\"needs\":[{\"id\":\"missing\",\"objective\":\"确认缺失概念\",\"query\":\"missing concept\"}]}"));
        gateway.add(decisionTool("m2", "retrieve_paper_evidence",
                "{\"needs\":[{\"id\":\"missing\",\"objective\":\"确认缺失概念\",\"query\":\"missing result\","
                        + "\"refinementReason\":\"首次没有命中，改用结果术语\"}]}"));
        gateway.add(decisionTool("m3", "submit_answer",
                "{\"groundingMode\":\"GENERAL_KNOWLEDGE\",\"answerBlocks\":[{\"text\":\"当前无法确认。\",\"sourceObjectIds\":[]}] }"));
        when(tools.execute(eq(catalog), eq("retrieve_paper_evidence"), anyString()))
                .thenReturn(
                        new AgentToolExecution("{\"status\":\"not_found\",\"sources\":[],\"evidenceNeeds\":[{\"needId\":\"missing\",\"status\":\"not_found\",\"sourceObjectIds\":[]}]}", Set.of()),
                        new AgentToolExecution("{\"status\":\"not_found\",\"sources\":[],\"evidenceNeeds\":[{\"needId\":\"missing\",\"status\":\"not_found\",\"sourceObjectIds\":[]}]}", Set.of()));

        AgentTurnResult result = service.execute(input("论文是否给出了该不存在概念的结果？"));

        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(gateway.requests.get(2).messages()).anyMatch(entry ->
                entry.role() == AgentChatEntry.Role.TOOL
                        && entry.content().contains("\"outcome\":\"no_match\"")
                        && entry.content().contains("\"recommendedAction\":\"refine_once\""));
        assertThat(gateway.requests.get(2).messages()).anyMatch(entry ->
                entry.role() == AgentChatEntry.Role.TOOL
                        && entry.content().contains("\"outcome\":\"no_match\"")
                        && entry.content().contains("\"recommendedAction\":\"stop\""));
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
        gateway.add(decisionTool("m1", "submit_answer",
                "{\"groundingMode\":\"GENERAL_KNOWLEDGE\",\"answerBlocks\":[{\"text\":\"迟到的回答不应被写入\",\"sourceObjectIds\":[]}] }"));

        AgentTurnResult result = service.execute(input("hello"));

        assertThat(result.status()).isEqualTo(AgentRunStatus.FAILED.name());
        assertThat(result.message()).contains("超时");
        verify(messages, times(1)).insert(any(com.research.assistant.entity.ResearchMessage.class));
        assertThat(result.message()).doesNotContain("迟到的回答不应被写入");
    }

    @Test
    void lateAnswerAfterCancellationDoesNotPersistAssistantMessage() {
        when(assembler.assemble(any())).thenReturn(context(null));
        when(runtime.getTurnForRun("run-1")).thenReturn(turn);
        doAnswer(invocation -> {
            AgentRunStatus target = invocation.getArgument(1);
            if (target == AgentRunStatus.COMPLETED) {
                run.setStatus(AgentRunStatus.CANCELLED.name());
                run.setErrorCode("USER_CANCELLED");
                throw new IllegalStateException("invalid AgentRun transition: CANCELLED -> COMPLETED");
            }
            return run;
        }).when(runtime).transitionRun(eq("run-1"), any(), ArgumentMatchers.nullable(String.class),
                ArgumentMatchers.nullable(String.class), ArgumentMatchers.nullable(String.class));
        gateway.add(decisionTool("m1", "submit_answer",
                "{\"groundingMode\":\"GENERAL_KNOWLEDGE\",\"answerBlocks\":[{\"text\":\"取消后的迟到回答不应被写入\",\"sourceObjectIds\":[]}] }"));

        AgentTurnResult result = service.execute(input("hello"));

        assertThat(result.status()).isEqualTo(AgentRunStatus.CANCELLED.name());
        assertThat(result.message()).isEqualTo("已取消回答");
        verify(messages, times(1)).insert(any(com.research.assistant.entity.ResearchMessage.class));
        assertThat(result.message()).doesNotContain("取消后的迟到回答不应被写入");
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
        gateway.add(decisionTool("m1", "submit_answer",
                "{\"groundingMode\":\"GENERAL_KNOWLEDGE\",\"answerBlocks\":[{\"text\":\"confirmed\",\"sourceObjectIds\":[]}] }"));
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

    private static AgentToolExecution evidenceResult(String sourceId) {
        return new AgentToolExecution(
                "{\"sources\":[{\"sourceObjectId\":\"" + sourceId + "\"}],"
                        + "\"evidenceNeeds\":[{\"needId\":\"formula\",\"sourceObjectIds\":[\""
                        + sourceId + "\"]}]}", Set.of(sourceId));
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

    private PaperSourceCatalog formulaCatalog() {
        SourceObject source = new SourceObject("src-formula", 9, "hash", "parser", 1,
                SourceContentType.FORMULA, "T = R(1 - epsilon) (12)", null,
                List.of("System model"), "12", Map.of("textReliable", "false"));
        SourceLocator locator = new SourceLocator("loc-formula", "src-formula", 3, "PDF_NORMALIZED",
                List.of(new NormalizedBoundingBox(.1, .2, .3, .08)), "(12)",
                EvidenceLocator.Precision.FORMULA_REGION);
        return new PaperSourceCatalog(9, "hash", "parser", 5,
                Map.of("src-formula", source), Map.of("src-formula", List.of(locator)));
    }

    private PaperSourceCatalog formulaAndTextCatalog() {
        PaperSourceCatalog formula = formulaCatalog();
        PaperSourceCatalog text = catalog();
        return new PaperSourceCatalog(9, "hash", "parser", 5,
                Map.of("src-formula", formula.objects().get("src-formula"),
                        "src-1", text.objects().get("src-1")),
                Map.of("src-formula", formula.locators().get("src-formula"),
                        "src-1", text.locators().get("src-1")));
    }

    private PaperSourceCatalog figureCatalog() {
        PaperSourceCatalog textCatalog = catalog();
        SourceObject figure = new SourceObject("src-figure", 9, "hash", "parser", 1,
                SourceContentType.FIGURE, "Figure 2. Spectral efficiency.", null,
                List.of("Results"), "", Map.of("visualRegion", "CAPTION_ANCHORED"));
        SourceLocator locator = new SourceLocator("loc-figure", "src-figure", 3, "PDF_NORMALIZED",
                List.of(new NormalizedBoundingBox(.5, .2, .4, .3)), "Figure 2. Spectral efficiency.",
                EvidenceLocator.Precision.VISUAL_REGION);
        return new PaperSourceCatalog(9, "hash", "parser", 5,
                Map.of("src-1", textCatalog.objects().get("src-1"), "src-figure", figure),
                Map.of("src-1", textCatalog.locators().get("src-1"), "src-figure", List.of(locator)));
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
