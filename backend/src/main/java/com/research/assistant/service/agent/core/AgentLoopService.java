package com.research.assistant.service.agent.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.research.assistant.dto.agent.AgentTurnInput;
import com.research.assistant.dto.agent.AgentTurnResult;
import com.research.assistant.dto.agent.AgentPendingAction;
import com.research.assistant.dto.agent.AgentEvidenceView;
import com.research.assistant.entity.AgentRunRecord;
import com.research.assistant.entity.AgentToolCallRecord;
import com.research.assistant.entity.AgentTurnRecord;
import com.research.assistant.entity.ResearchMessage;
import com.research.assistant.mapper.ResearchMessageMapper;
import com.research.assistant.service.agent.runtime.AgentRunBudget;
import com.research.assistant.service.agent.runtime.AgentRunStatus;
import com.research.assistant.service.agent.runtime.AgentRuntimeService;
import com.research.assistant.service.agent.runtime.AgentToolCallStatus;
import com.research.assistant.service.agent.runtime.AgentRunFailureClassifier;
import com.research.assistant.service.agent.runtime.AgentAttachmentService;
import com.research.assistant.service.agent.runtime.AgentConversationSummaryService;
import com.research.assistant.service.agent.source.CitationRequest;
import com.research.assistant.service.agent.source.GroundEvidenceService;
import com.research.assistant.service.agent.source.GroundedAnswer;
import com.research.assistant.service.agent.source.SourceEvidenceQuality;
import com.research.assistant.service.agent.source.SourceEvidenceIdentity;
import com.research.assistant.service.agent.source.SourceObject;
import com.research.assistant.service.agent.action.ActionTicketService;
import com.research.assistant.service.agent.action.PaperActionResolver;
import com.research.assistant.service.agent.capability.AiCapabilityService;
import com.research.assistant.service.agent.skill.PaperActionSkillTool;
import com.research.assistant.service.agent.skill.PaperEvidenceSkillTool;
import com.research.assistant.service.agent.skill.PaperProfileSkillTool;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.LinkedHashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class AgentLoopService {
    private static final Pattern DISPLAY_MATH = Pattern.compile(
            "(?s)\\$\\$.*?\\$\\$|\\\\\\[.*?\\\\\\]");
    private static final Pattern SOURCE_LABEL = Pattern.compile("\\[(S\\d{1,3})]", Pattern.CASE_INSENSITIVE);

    // Interactive paper answers retain a final liveness boundary if a provider call stalls.
    // Evidence-phase convergence is enforced separately from this wall-clock safeguard.
    private static final AgentRunBudget DEFAULT_BUDGET = AgentRunBudget.defaults();
    private static final String FINISH_RESEARCH_SCHEMA = """
            {"type":"object","properties":{
            "groundingMode":{"type":"string","enum":["PAPER","GENERAL_KNOWLEDGE","MIXED"],
            "description":"最终内容只陈述论文事实时为 PAPER；完全不依赖论文时为 GENERAL_KNOWLEDGE；两者兼有时为 MIXED。"},
            "responseMode":{"type":"string","enum":["CONTENT","ACTION_ONLY","CONTENT_AND_ACTION"],
            "description":"只回答内容为 CONTENT；只执行页面操作为 ACTION_ONLY，用户要求完成后告知结果仍属于 ACTION_ONLY；既要求独立的论文回答或解释、又要求页面操作时才使用 CONTENT_AND_ACTION。"}},
            "required":["groundingMode","responseMode"],"additionalProperties":false}
            """;
    private static final String CLARIFICATION_SCHEMA = """
            {"type":"object","properties":{"question":{"type":"string","description":"需要用户补充的简短中文问题。"}},
            "required":["question"],"additionalProperties":false}
            """;

    private final AgentRuntimeService runtimeService;
    private final AgentContextAssembler contextAssembler;
    private final AgentModelSnapshotService snapshotService;
    private final PaperAgentFrameworkExecutor frameworkExecutor;
    private final PaperEvidenceSkillTool evidenceSkillTool;
    private final PaperProfileSkillTool profileSkillTool;
    private final PaperActionSkillTool actionSkillTool;
    private final GroundEvidenceService evidenceService;
    private final ResearchMessageMapper messageMapper;
    private final ObjectMapper objectMapper;
    private final ActionTicketService ticketService;
    private final AiCapabilityService capabilityService;
    private final AgentAttachmentService attachmentService;
    private final AgentSkillRegistry skillRegistry;
    private final AgentConversationSummaryService summaryService;

    @org.springframework.beans.factory.annotation.Autowired
    public AgentLoopService(AgentRuntimeService runtimeService, AgentContextAssembler contextAssembler,
                            AgentModelSnapshotService snapshotService, PaperAgentFrameworkExecutor frameworkExecutor,
                            PaperEvidenceSkillTool evidenceSkillTool, PaperProfileSkillTool profileSkillTool,
                            PaperActionSkillTool actionSkillTool, GroundEvidenceService evidenceService,
                            ResearchMessageMapper messageMapper, ObjectMapper objectMapper,
                            ActionTicketService ticketService,
                            AiCapabilityService capabilityService, AgentAttachmentService attachmentService,
                            AgentSkillRegistry skillRegistry,
                            AgentConversationSummaryService summaryService) {
        this.runtimeService = runtimeService;
        this.contextAssembler = contextAssembler;
        this.snapshotService = snapshotService;
        this.frameworkExecutor = frameworkExecutor;
        this.evidenceSkillTool = evidenceSkillTool;
        this.profileSkillTool = profileSkillTool;
        this.actionSkillTool = actionSkillTool;
        this.evidenceService = evidenceService;
        this.messageMapper = messageMapper;
        this.objectMapper = objectMapper;
        this.ticketService = ticketService;
        this.capabilityService = capabilityService;
        this.attachmentService = attachmentService;
        this.skillRegistry = skillRegistry;
        this.summaryService = summaryService;
    }

    /** Constructor retained for focused tests and small embedders. */
    public AgentLoopService(AgentRuntimeService runtimeService, AgentContextAssembler contextAssembler,
                            AgentModelSnapshotService snapshotService, PaperAgentFrameworkExecutor frameworkExecutor,
                            PaperReadToolRegistry toolRegistry, GroundEvidenceService evidenceService,
                            ResearchMessageMapper messageMapper, ObjectMapper objectMapper,
                            PaperActionResolver actionResolver, ActionTicketService ticketService,
                            AiCapabilityService capabilityService, AgentAttachmentService attachmentService) {
        this(runtimeService, contextAssembler, snapshotService, frameworkExecutor,
                new PaperEvidenceSkillTool(toolRegistry), null, new PaperActionSkillTool(actionResolver),
                evidenceService, messageMapper, objectMapper, ticketService, capabilityService, attachmentService,
                new AgentSkillRegistry(new PaperEvidenceSkillTool(toolRegistry), null,
                        new PaperActionSkillTool(actionResolver), java.nio.file.Path.of("../skills")), null);
    }

    public AgentTurnResult execute(AgentTurnInput input) {
        PreparedTurn prepared = prepare(input);
        if (prepared.duplicateRequest()) return currentResult(prepared.run().getRunId());
        return executePrepared(prepared);
    }

    PreparedTurn prepare(AgentTurnInput input) {
        AgentContextSnapshot context = contextAssembler.assemble(input);
        AgentTurnRecord turn;
        AgentRunRecord run;
        boolean duplicateRequest = false;
        if (input.resumeRunId() != null) {
            run = runtimeService.getRun(input.resumeRunId());
            if (!AgentRunStatus.WAITING_USER.name().equals(run.getStatus())) {
                throw new IllegalArgumentException("only a run waiting for user clarification can be resumed");
            }
            turn = runtimeService.getTurnForRun(run.getRunId());
            if (turn.getSessionId() != input.conversationId()) throw new IllegalArgumentException("resume run belongs to another conversation");
            saveUserMessage(input, turn, run.getRunId());
            run = runtimeService.transitionRun(run.getRunId(), AgentRunStatus.RUNNING, null, null, null);
        } else {
            String messageKey = "agent-user-" + input.clientRequestId();
            turn = runtimeService.createTurn(input.conversationId(), input.clientRequestId(), messageKey);
            ResearchMessage existing = messageMapper.selectByMessageKey(input.conversationId(), messageKey);
            duplicateRequest = existing != null;
            if (existing == null) existing = saveUserMessage(input, turn, null);
            run = runtimeService.startRun(turn.getTurnId(), snapshotService.current(), DEFAULT_BUDGET,
                    AgentContextAssembler.SCHEMA_VERSION, context.snapshotJson(),
                    context.sourceCatalog() == null ? null : context.sourceCatalog().documentHash(),
                    context.sourceCatalog() == null ? null : context.sourceCatalog().parserVersion());
            if (existing.getRunId() == null) messageMapper.bindRun(existing.getId(), run.getRunId());
            claimAttachments(input, turn);
        }

        if (input.resumeRunId() != null) claimAttachments(input, turn);
        return new PreparedTurn(input, context, turn, run, duplicateRequest);
    }

    AgentTurnResult executePrepared(PreparedTurn prepared) {
        AgentTurnInput input = prepared.input();
        AgentContextSnapshot context = prepared.context();
        AgentTurnRecord turn = prepared.turn();
        AgentRunRecord currentRun = prepared.run();
        if (AgentRunStatus.QUEUED.name().equals(currentRun.getStatus())) {
            // started_at is set by this transition, after the executor has begun work.
            // This keeps queue latency out of the model run deadline.
            currentRun = runtimeService.transitionRun(currentRun.getRunId(), AgentRunStatus.RUNNING,
                    null, null, null);
        }
        AgentRunRecord run = currentRun;
        if (input.explicitAction() != null) {
            return executeExplicitAction(input, context, turn, run);
        }
        // Capability records are advisory diagnostics. A transient probe failure must not
        // disable ordinary chat; the actual model invocation remains the source of truth.

        Set<String> readSources = new LinkedHashSet<>(context.preReadSourceIds());
        Set<String> visuallyReadSources = new LinkedHashSet<>();
        Map<String, AgentToolExecution> readToolCache = new HashMap<>();
        EvidenceReadState evidenceReadState = new EvidenceReadState();
        RunOutputState outputState = new RunOutputState();
        evidenceReadState.registerSources(readSources);
        List<AgentToolDefinition> definitions = new ArrayList<>();
        List<AgentSkillBinding> skills = skillRegistry.bindings(context, input.userMessage());
        definitions.add(new AgentToolDefinition("finish_research",
                "所需 Skill 和读取工具已经使用完毕后调用，并一次声明 groundingMode 与 responseMode。该工具不接收答案；包含页面操作的输出必须先通过 paper_action 提交完整计划。调用成功后工具会关闭，下一次模型响应直接输出给用户。PAPER/MIXED 必须已有原文证据，并使用 [S1]、[S2] 等短标签标注论文事实；通用知识问题使用 GENERAL_KNOWLEDGE。",
                FINISH_RESEARCH_SCHEMA));
        definitions.add(new AgentToolDefinition("ask_clarification",
                "仅当页面操作或答案实质依赖缺失或含糊的用户意图时，提出一个简短的自然语言澄清问题。普通可解问题不要使用。",
                CLARIFICATION_SCHEMA));
        List<AgentChatEntry> messages = context.messages();
        try {
            AgentFrameworkResult frameworkResult = frameworkExecutor.execute(messages, definitions, skills,
                    request -> executeFrameworkTool(context, turn, run.getRunId(), readSources,
                            visuallyReadSources, readToolCache, evidenceReadState, outputState,
                            input.userMessage(), request),
                    (toolCallId, skillName, argumentsJson, instructions) -> {
                        persistSkillActivation(run.getRunId(), toolCallId, skillName, argumentsJson, instructions);
                        outputState.activate(skillName);
                    },
                    trace -> runtimeService.recordModelCall(run.getRunId(), trace));
            runtimeService.recordUsage(run.getRunId(), frameworkResult.modelCalls(), frameworkResult.toolCalls(),
                    frameworkResult.promptTokens(), frameworkResult.completionTokens());
            if (frameworkResult.content() != null && !frameworkResult.content().isBlank()) {
                try {
                    AgentTurnResult structured = readResult(frameworkResult.content());
                    if (isStoredResult(structured, run.getRunId())) return structured;
                } catch (com.fasterxml.jackson.core.JsonProcessingException notStructured) {
                    // Expected for the final no-tool Markdown response.
                }
                return completePlainAnswer(context, turn, run, frameworkResult.content(),
                        readSources, visuallyReadSources, evidenceReadState, outputState);
            }
            throw new IllegalStateException("MODEL_EMPTY_RESPONSE: 模型返回了空响应");
        } catch (AgentFrameworkExecutionException failure) {
            AgentFrameworkResult usage = failure.usage();
            runtimeService.recordUsage(run.getRunId(), usage.modelCalls(), usage.toolCalls(),
                    usage.promptTokens(), usage.completionTokens());
            // Framework failures used to be left RUNNING and were later rewritten
            // as RUN_TIMEOUT by the watchdog.  Persist the actual stable failure
            // code immediately so local guards and provider failures can be
            // distinguished and evaluated correctly.
            AgentRunRecord current = runtimeService.getRun(run.getRunId());
            if (AgentRunStatus.RUNNING.name().equals(current.getStatus())) {
                AgentRunFailureClassifier.Failure classified = AgentRunFailureClassifier.classify(failure);
                runtimeService.transitionRun(run.getRunId(), AgentRunStatus.FAILED, null,
                        classified.code(), safeError(failure));
            }
            throw failure;
        } catch (Exception error) {
            AgentRunRecord current = runtimeService.getRun(run.getRunId());
            if (AgentRunStatus.RUNNING.name().equals(current.getStatus())) {
                AgentRunFailureClassifier.Failure failure = AgentRunFailureClassifier.classify(error);
                runtimeService.transitionRun(run.getRunId(), AgentRunStatus.FAILED, null,
                        failure.code(), safeError(error));
            }
            throw error instanceof RuntimeException runtime ? runtime : new IllegalStateException(error);
        }
    }

    private AgentToolExecution executeFrameworkTool(AgentContextSnapshot context,
                                        AgentTurnRecord turn,
                                        String runId,
                                        Set<String> readSources,
                                        Set<String> visuallyReadSources,
                                        Map<String, AgentToolExecution> readToolCache,
                                        EvidenceReadState evidenceReadState,
                                        RunOutputState outputState,
                                        String evidenceFocus,
                                        AgentToolRequest request) {
        boolean mutation = actionSkillTool.supports(request.name());
        AgentToolCallRecord call = runtimeService.registerToolCall(runId, request.name(),
                request.argumentsJson(), !mutation, runId + ":" + request.id());
        call = runtimeService.transitionToolCall(call.getToolCallId(), AgentToolCallStatus.RUNNING,
                null, null, null);
        try {
            if ("finish_research".equals(request.name())) {
                JsonNode arguments = objectMapper.readTree(request.argumentsJson());
                String groundingMode = requiredText(arguments, "groundingMode");
                String responseMode = requiredText(arguments, "responseMode");
                outputState.finish(groundingMode, responseMode);
                if ((outputState.actionRequired() || outputState.actionOutputRequired())
                        && !outputState.hasActionPlan()) {
                    String resultJson = "{\"status\":\"action_plan_required\",\"message\":\"本轮输出包含页面操作，请先通过 paper_action 提交完整操作计划，再以相同 responseMode 调用 finish_research。\"}";
                    runtimeService.transitionToolCall(call.getToolCallId(), AgentToolCallStatus.COMPLETED,
                            resultJson, null, null);
                    return toolResult(resultJson);
                }
                if (outputState.hasActionPlan() && !outputState.actionOutputRequired()) {
                    String resultJson = "{\"status\":\"action_response_mode_required\",\"message\":\"页面操作计划已经提交；请将 responseMode 设为 ACTION_ONLY 或 CONTENT_AND_ACTION。\"}";
                    runtimeService.transitionToolCall(call.getToolCallId(), AgentToolCallStatus.COMPLETED,
                            resultJson, null, null);
                    return toolResult(resultJson);
                }
                if (outputState.paperGroundingRequired() && !evidenceReadState.hasUsableEvidence()) {
                    String resultJson = "{\"status\":\"paper_evidence_required\",\"message\":\"PAPER/MIXED 内容需要先读取论文原文证据。\"}";
                    runtimeService.transitionToolCall(call.getToolCallId(), AgentToolCallStatus.COMPLETED,
                            resultJson, null, null);
                    return toolResult(resultJson);
                }
                String resultJson = finalAnswerInstructions(context, evidenceReadState, outputState);
                runtimeService.transitionToolCall(call.getToolCallId(), AgentToolCallStatus.COMPLETED,
                        resultJson, null, null);
                return toolResult(resultJson);
            }
            if ("ask_clarification".equals(request.name())) {
                String question = requiredText(objectMapper.readTree(request.argumentsJson()), "question");
                validateModelVisibleText(question, "澄清问题");
                AgentTurnResult waiting = new AgentTurnResult(turn.getTurnId(), runId,
                        AgentRunStatus.WAITING_USER.name(), question, List.of(), List.of());
                String resultJson = writeResult(waiting);
                if (!transitionRunBeforePersistingResult(runId, AgentRunStatus.WAITING_USER, resultJson)) {
                    failLateToolCall(call);
                    return toolResult(writeResult(currentResult(runId)));
                }
                runtimeService.transitionToolCall(call.getToolCallId(), AgentToolCallStatus.COMPLETED,
                        resultJson, null, null);
                saveAssistantMessage(turn, runId, "CLARIFICATION", question, null);
                return toolResult(resultJson);
            }
            if (actionSkillTool.supports(request.name())) {
                PaperActionSkillTool.PreparedActionPlan plan = actionSkillTool.preparePlan(
                        context.sourceCatalog(), readSources, request.argumentsJson(), objectMapper);
                outputState.plan(call, request, plan);
                ObjectNode planned = objectMapper.createObjectNode();
                planned.put("status", "action_planned");
                planned.put("actionCount", plan.actions().size());
                planned.put("instructions", "操作计划已校验。完成其余研究后调用 finish_research，并将 responseMode 设为 ACTION_ONLY 或 CONTENT_AND_ACTION；页面完成状态由客户端回执产生。");
                return toolResult(objectMapper.writeValueAsString(planned));
            }
            if (profileSkillTool != null && profileSkillTool.supports(request.name())) {
                if (context.paperId() == null) {
                    throw new IllegalArgumentException("论文画像不可用");
                }
                String documentHash = context.sourceCatalog() == null ? "" : context.sourceCatalog().documentHash();
                String profileKey = request.name() + "\n" + context.paperId() + "\n" + documentHash;
                AgentToolExecution overview;
                synchronized (readToolCache) {
                    AgentToolExecution cached = readToolCache.get(profileKey);
                    if (cached == null) {
                        overview = profileSkillTool.execute(context.paperId(), context.sourceCatalog());
                        readToolCache.put(profileKey, overview);
                    } else {
                        overview = idempotentToolResult(request.name(), cached,
                                "当前论文版本的画像已经在本轮上下文中可用，请直接使用已有画像判断下一步。");
                    }
                }
                // 画像返回的来源只是后续证据检索的候选锚点；只有 paper-evidence
                // 的读取结果才能进入本轮可引用来源集合。
                runtimeService.transitionToolCall(call.getToolCallId(), AgentToolCallStatus.COMPLETED,
                        overview.resultJson(), null, null);
                return overview;
            }
            if (context.sourceCatalog() == null) throw new IllegalArgumentException("论文来源尚未就绪");
            AgentToolExecution result;
            // Cache is a local execution optimization for an identical read request. It is
            // not a semantic rule: a changed query remains fully available to the Agent.
            String effectiveArguments = evidenceArguments(request.name(), request.argumentsJson(), evidenceFocus);
            String cacheKey = request.name() + "\n" + canonicalArguments(effectiveArguments);
            synchronized (readToolCache) {
                AgentToolExecution cached = readToolCache.get(cacheKey);
                if (cached == null) {
                    result = evidenceSkillTool.execute(context.sourceCatalog(), request.name(), effectiveArguments);
                    readToolCache.put(cacheKey, result);
                } else {
                    result = idempotentToolResult(request.name(), cached,
                            "完全相同的读取已经完成；已有结果仍在本轮工作状态中。请修改检索目标以获取新信息，或完成回答。");
                }
            }
            if (evidenceSkillTool.supports(request.name())) {
                outputState.markPaperEvidenceUsed();
                if (!result.sourceObjectIds().isEmpty()) {
                    evidenceReadState.markUsableEvidence();
                }
                result = addEvidenceProgress(result, effectiveArguments, evidenceReadState);
                result = addCitationLabels(result, evidenceReadState);
            }
            readSources.addAll(result.sourceObjectIds());
            evidenceReadState.registerSources(result.sourceObjectIds());
            result.visuals().forEach(visual -> {
                visuallyReadSources.add(visual.sourceObjectId());
                if ("FIGURE".equals(visual.contentType())) {
                    evidenceReadState.requiredFigureSourceIds.add(visual.sourceObjectId());
                }
            });
            runtimeService.transitionToolCall(call.getToolCallId(), AgentToolCallStatus.COMPLETED,
                    result.resultJson(), null, null);
            return result;
        } catch (Exception error) {
            runtimeService.transitionToolCall(call.getToolCallId(), AgentToolCallStatus.FAILED, null,
                    "TOOL_INPUT_OR_EXECUTION_FAILED", safeError(error));
            if (evidenceSkillTool.supports(request.name())) {
                return toolResult(readUnavailableResult());
            }
            throw error instanceof RuntimeException runtime ? runtime : new IllegalArgumentException(error);
        }
    }

    private String finalAnswerInstructions(AgentContextSnapshot context, EvidenceReadState state,
                                           RunOutputState outputState) throws Exception {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("status", "ready_for_answer");
        ArrayNode sources = payload.putArray("citationSources");
        for (Map.Entry<String, String> entry : state.sourceIdsByLabel.entrySet()) {
            SourceObject source = context.sourceCatalog() == null
                    ? null : context.sourceCatalog().objects().get(entry.getValue());
            if (!usableFinalSource(source, context)) continue;
            ObjectNode item = sources.addObject();
            item.put("label", entry.getKey());
            item.put("sourceObjectId", entry.getValue());
            item.put("contentType", source.contentType().name());
        }
        String actionInstruction = "CONTENT_AND_ACTION".equals(outputState.responseMode())
                ? "本次页面操作结果将由客户端回执追加；这里只写用户要求的独立内容答案，不描述操作计划、提交、等待或完成状态。"
                : "页面操作是否完成只由客户端回执确认，不得声称跳转、高亮、批注等操作已经完成。";
        if (sources.isEmpty()) {
            payload.put("instructions",
                    "现在直接输出给用户的最终 Markdown。不要调用工具，不要输出工作流或诊断信息。没有可靠论文证据时，只回答能够确认的内容。" + actionInstruction);
        } else {
            payload.put("instructions",
                    "现在直接输出给用户的最终 Markdown。论文事实在相关句末使用 [S1]、[S2] 等 citationSources 标签；不要输出 sourceObjectId，不要调用工具，也不要输出工作流或诊断信息。只写已读证据支持的内容。" + actionInstruction);
        }
        return objectMapper.writeValueAsString(payload);
    }

    private AgentToolExecution addCitationLabels(AgentToolExecution execution, EvidenceReadState state) {
        state.registerSources(execution.sourceObjectIds());
        try {
            JsonNode parsed = objectMapper.readTree(execution.resultJson());
            if (!(parsed instanceof ObjectNode root)) return execution;
            ObjectNode copy = root.deepCopy();
            appendCitationLabels(copy.path("sources"), state);
            appendCitationLabels(copy.path("visualSources"), state);
            copy.put("citationFormat", "在最终 Markdown 的相关句末使用 [S1]、[S2] 等 citationLabel；不要复制 sourceObjectId。");
            return new AgentToolExecution(objectMapper.writeValueAsString(copy),
                    execution.sourceObjectIds(), execution.visuals());
        } catch (Exception ignored) {
            return execution;
        }
    }

    private void appendCitationLabels(JsonNode sources, EvidenceReadState state) {
        if (!sources.isArray()) return;
        for (JsonNode source : sources) {
            if (!(source instanceof ObjectNode item)) continue;
            String sourceId = item.path("sourceObjectId").asText("").trim();
            String label = state.labelFor(sourceId);
            if (label != null) item.put("citationLabel", label);
        }
    }

    private AgentTurnResult completePlainAnswer(AgentContextSnapshot context, AgentTurnRecord turn,
                                                AgentRunRecord run, String rawAnswer,
                                                Set<String> readSources,
                                                Set<String> visuallyReadSources,
                                                EvidenceReadState evidenceReadState,
                                                RunOutputState outputState) throws Exception {
        if (outputState.hasActionPlan()) {
            return waitForPlannedActions(context, turn, run, rawAnswer, readSources,
                    visuallyReadSources, evidenceReadState, outputState);
        }
        GroundedAnswer grounded = groundPlainAnswer(rawAnswer, context, readSources,
                visuallyReadSources, evidenceReadState, outputState);
        List<AgentEvidenceView> evidence = evidenceViews(grounded, context);
        AgentTurnResult completed = new AgentTurnResult(turn.getTurnId(), run.getRunId(),
                AgentRunStatus.COMPLETED.name(), grounded.answer(), grounded.bindings(), evidence);
        String resultJson = writeResult(completed);
        if (!transitionRunBeforePersistingResult(run.getRunId(), AgentRunStatus.COMPLETED, resultJson)) {
            return currentResult(run.getRunId());
        }
        saveAssistantMessage(turn, run.getRunId(), "CHAT", grounded.answer(), resultJson);
        return completed;
    }

    private AgentTurnResult waitForPlannedActions(AgentContextSnapshot context, AgentTurnRecord turn,
                                                   AgentRunRecord run, String rawAnswer,
                                                   Set<String> readSources,
                                                   Set<String> visuallyReadSources,
                                                   EvidenceReadState evidenceReadState,
                                                   RunOutputState outputState) throws Exception {
        PlannedActionOutput planned = outputState.requirePlan();
        boolean withContent = outputState.contentRequired();
        GroundedAnswer grounded = withContent
                ? groundPlainAnswer(rawAnswer, context, readSources,
                        visuallyReadSources, evidenceReadState, outputState)
                : new GroundedAnswer("", List.of(), List.of());
        List<AgentPendingAction> actions = new ArrayList<>();
        for (int index = 0; index < planned.plan().actions().size(); index++) {
            PaperActionSkillTool.PreparedAction prepared = planned.plan().actions().get(index);
            AgentToolCallRecord actionCall = planned.primaryCall();
            if (index > 0) {
                String arguments = actionArgumentsForTarget(planned.request().argumentsJson(), prepared);
                actionCall = runtimeService.registerToolCall(run.getRunId(), "paper_action", arguments,
                        false, run.getRunId() + ":" + planned.request().id() + ":target:" + index);
                actionCall = runtimeService.transitionToolCall(actionCall.getToolCallId(),
                        AgentToolCallStatus.RUNNING, null, null, null);
            }
            ActionTicketService.IssuedActionTicket issued = ticketService.issue(run.getRunId(), actionCall,
                    prepared.type(), prepared.target(), prepared.content(), prepared.color());
            actions.add(new AgentPendingAction(actionCall.getToolCallId(), prepared.type(), prepared.target(),
                    prepared.content(), prepared.color(), issued.ticket(), issued.expiresAt()));
        }
        List<AgentEvidenceView> evidence = withContent ? evidenceViews(grounded, context) : List.of();
        String message = withContent ? grounded.answer() : "正在执行页面操作。";
        AgentTurnResult waiting = new AgentTurnResult(turn.getTurnId(), run.getRunId(),
                AgentRunStatus.WAITING_CLIENT.name(), message,
                withContent ? grounded.bindings() : List.of(), evidence, actions,
                outputState.responseMode());
        String resultJson = writeResult(waiting);
        if (!transitionRunBeforePersistingResult(run.getRunId(), AgentRunStatus.WAITING_CLIENT, resultJson)) {
            return currentResult(run.getRunId());
        }
        return waiting;
    }

    private GroundedAnswer groundPlainAnswer(String rawAnswer, AgentContextSnapshot context,
                                             Set<String> readSources,
                                             Set<String> visuallyReadSources,
                                             EvidenceReadState state,
                                             RunOutputState outputState) {
        ParsedFinalAnswer parsed = parseFinalAnswer(rawAnswer, state);
        if (parsed.text().isBlank()) throw new IllegalArgumentException("模型返回了空响应");
        if (context.sourceCatalog() == null || readSources.isEmpty()
                || !outputState.paperGroundingRequired()) {
            return new GroundedAnswer(parsed.text(), List.of(), List.of());
        }
        List<CitationRequest> requests = new ArrayList<>();
        for (CitationAnchor anchor : parsed.citations()) {
            if (!readSources.contains(anchor.sourceObjectId())
                    || !usableFinalSource(context.sourceCatalog().objects().get(anchor.sourceObjectId()), context)) {
                continue;
            }
            requests.add(new CitationRequest(anchor.answerStart(), anchor.answerEnd(),
                    anchor.sourceObjectId(), null, List.of()));
        }
        if (requests.isEmpty()) {
            state.sourceIdsByLabel.values().stream()
                    .filter(readSources::contains)
                    .filter(sourceId -> usableFinalSource(context.sourceCatalog().objects().get(sourceId), context))
                    .limit(8)
                    .forEach(sourceId -> requests.add(new CitationRequest(
                            0, parsed.text().length(), sourceId, null, List.of())));
        }
        if (containsDisplayMath(parsed.text()) && !requests.isEmpty()) {
            ArrayNode submitted = objectMapper.createArrayNode();
            requests.stream().map(CitationRequest::sourceObjectId).distinct().forEach(submitted::add);
            if (!hasReliableFormulaSupport(submitted, context, visuallyReadSources)) {
                List<String> formulaSources = reliableReadFormulaSources(context, readSources, visuallyReadSources);
                if (formulaSources.isEmpty()) {
                    String safeText = omitUnsupportedDisplayMath(parsed.text());
                    if (safeText.isBlank()) safeText = "（当前已读来源不足以可靠展示该公式）";
                    return new GroundedAnswer(safeText, List.of(), List.of());
                }
                formulaSources.stream().filter(sourceId -> requests.stream()
                                .noneMatch(request -> sourceId.equals(request.sourceObjectId())))
                        .forEach(sourceId -> requests.add(new CitationRequest(
                                0, parsed.text().length(), sourceId, null, List.of())));
            }
        }
        state.requiredFigureSourceIds.stream()
                .filter(readSources::contains)
                .filter(sourceId -> requests.stream().noneMatch(request -> sourceId.equals(request.sourceObjectId())))
                .forEach(sourceId -> requests.add(new CitationRequest(
                        0, parsed.text().length(), sourceId, null, List.of())));
        if (requests.isEmpty()) return new GroundedAnswer(parsed.text(), List.of(), List.of());
        return evidenceService.ground(parsed.text(), requests, context.sourceCatalog());
    }

    private ParsedFinalAnswer parseFinalAnswer(String rawAnswer, EvidenceReadState state) {
        String value = sanitizeModelText(rawAnswer);
        Matcher matcher = SOURCE_LABEL.matcher(value);
        List<CitationAnchor> citations = new ArrayList<>();
        StringBuilder text = new StringBuilder(value.length());
        int start = 0;
        while (matcher.find()) {
            text.append(value, start, matcher.start());
            String sourceId = state.sourceId(matcher.group(1));
            if (sourceId != null && text.length() > 0) {
                citations.add(new CitationAnchor(sourceId, citationStart(text), text.length()));
            }
            start = matcher.end();
        }
        text.append(value, start, value.length());
        String cleaned = stripModelCitationMarkers(text.toString())
                .replaceAll("[ \\t]+(?=\\R|$)", "")
                .replaceAll("(?:\\R\\s*){3,}", "\n\n")
                .trim();
        int removedPrefix = text.indexOf(cleaned);
        if (removedPrefix < 0) removedPrefix = 0;
        int prefix = removedPrefix;
        List<CitationAnchor> adjusted = citations.stream()
                .map(citation -> new CitationAnchor(citation.sourceObjectId(),
                        Math.max(0, citation.answerStart() - prefix),
                        Math.min(cleaned.length(), citation.answerEnd() - prefix)))
                .filter(citation -> citation.answerEnd() > citation.answerStart())
                .toList();
        return new ParsedFinalAnswer(cleaned, adjusted);
    }

    private static int citationStart(StringBuilder text) {
        int end = text.length();
        int paragraph = text.lastIndexOf("\n\n", Math.max(0, end - 1));
        int start = paragraph < 0 ? 0 : paragraph + 2;
        while (start < end && Character.isWhitespace(text.charAt(start))) start++;
        return start;
    }

    private static String sanitizeModelText(String value) {
        if (value == null) return "";
        StringBuilder output = new StringBuilder(value.length());
        for (int index = 0; index < value.length();) {
            int codePoint = value.codePointAt(index);
            boolean whitespace = codePoint == '\t' || codePoint == '\n' || codePoint == '\r';
            if (!Character.isISOControl(codePoint) || whitespace) output.appendCodePoint(codePoint);
            index += Character.charCount(codePoint);
        }
        return output.toString();
    }

    private static boolean usableFinalSource(SourceObject source, AgentContextSnapshot context) {
        return source != null && context.sourceCatalog() != null
                && source.paperId() == context.sourceCatalog().paperId()
                && source.documentHash().equals(context.sourceCatalog().documentHash())
                && source.parserVersion().equals(context.sourceCatalog().parserVersion())
                && context.sourceCatalog().locators().containsKey(source.sourceObjectId())
                && !context.sourceCatalog().locators().get(source.sourceObjectId()).isEmpty()
                && SourceEvidenceQuality.usableForCitation(source);
    }

    private record CitationAnchor(String sourceObjectId, int answerStart, int answerEnd) { }
    private record ParsedFinalAnswer(String text, List<CitationAnchor> citations) { }

    private void persistSkillActivation(String runId, String toolCallId, String skillName,
                                        String argumentsJson, String instructions) {
        if (runId == null || skillName == null || skillName.isBlank()
                || instructions == null || instructions.isBlank()) return;
        try {
            String stableRequestId = toolCallId == null || toolCallId.isBlank()
                    ? "activation-" + Integer.toHexString((skillName + argumentsJson).hashCode())
                    : toolCallId;
            AgentToolCallRecord call = runtimeService.registerToolCall(runId, "activate_skill",
                    argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson,
                    true, runId + ":activate:" + stableRequestId);
            call = runtimeService.transitionToolCall(call.getToolCallId(), AgentToolCallStatus.RUNNING,
                    null, null, null);
            String resultJson = objectMapper.writeValueAsString(Map.of(
                    "skillName", skillName.trim(),
                    "instructions", instructions));
            runtimeService.transitionToolCall(call.getToolCallId(), AgentToolCallStatus.COMPLETED,
                    resultJson, null, null);
        } catch (Exception ignored) {
            // Skill activation has already completed in the model loop. Its
            // transcript persistence is only for continuity on later turns.
        }
    }

    private String evidenceArguments(String toolName, String argumentsJson, String evidenceFocus) {
        if (!"retrieve_paper_evidence".equals(toolName)
                || evidenceFocus == null || evidenceFocus.isBlank()) return argumentsJson;
        try {
            JsonNode parsed = objectMapper.readTree(argumentsJson);
            if (parsed.isObject()) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) parsed).put("_evidenceFocus", evidenceFocus);
                return objectMapper.writeValueAsString(parsed);
            }
        } catch (Exception ignored) {
            // The tool registry will report the original malformed arguments consistently.
        }
        return argumentsJson;
    }

    private String actionArgumentsForTarget(String argumentsJson,
                                            PaperActionSkillTool.PreparedAction preparedAction) {
        try {
            JsonNode parsed = objectMapper.readTree(argumentsJson);
            if (!parsed.isObject()) throw new IllegalArgumentException("页面操作参数必须是对象");
            com.fasterxml.jackson.databind.node.ObjectNode target;
            if (parsed.path("operations").isArray() && preparedAction.operationIndex() >= 0
                    && preparedAction.operationIndex() < parsed.path("operations").size()) {
                JsonNode operation = parsed.path("operations").get(preparedAction.operationIndex());
                if (!operation.isObject()) throw new IllegalArgumentException("页面操作参数必须是对象");
                target = ((com.fasterxml.jackson.databind.node.ObjectNode) operation).deepCopy();
            } else {
                target = (com.fasterxml.jackson.databind.node.ObjectNode) parsed;
            }
            target.remove("sourceObjectIds");
            target.put("sourceObjectId", preparedAction.target().sourceObjectId());
            target.remove("operations");
            return objectMapper.writeValueAsString(target);
        } catch (Exception error) {
            throw new IllegalArgumentException("页面操作参数无效", error);
        }
    }

    private String evidenceNeedFingerprint(JsonNode need) {
        try {
            com.fasterxml.jackson.databind.node.ObjectNode normalized = objectMapper.createObjectNode();
            putNormalizedText(normalized, need, "query", true);
            putNormalizedTextArray(normalized, need, "keywords", true);
            putNormalizedTextArray(normalized, need, "targets", true);
            putNormalizedText(normalized, need, "sectionHint", true);
            putSortedIntegers(normalized, need, "pageHints");
            putNormalizedTextArray(normalized, need, "profileClaimRefs", false);
            putNormalizedTextArray(normalized, need, "contentTypes", true);
            putNormalizedTextArray(normalized, need, "sourceObjectIds", false);
            if (need.has("includeVisual")) {
                normalized.put("includeVisual", need.path("includeVisual").asBoolean(false));
            }
            if (need.has("cursor") && need.path("cursor").isIntegralNumber()) {
                normalized.put("cursor", need.path("cursor").asInt());
            }
            return objectMapper.writeValueAsString(normalized);
        } catch (Exception ignored) {
            return canonicalArguments(need == null ? "" : need.toString());
        }
    }

    private void putNormalizedText(com.fasterxml.jackson.databind.node.ObjectNode target, JsonNode source,
                                   String field, boolean lowerCase) {
        if (!source.has(field)) return;
        String value = normalizedText(source.path(field).asText(""));
        target.put(field, lowerCase ? value.toLowerCase(java.util.Locale.ROOT) : value);
    }

    private void putNormalizedTextArray(com.fasterxml.jackson.databind.node.ObjectNode target, JsonNode source,
                                        String field, boolean lowerCase) {
        if (!source.path(field).isArray()) return;
        List<String> values = new ArrayList<>();
        for (JsonNode item : source.path(field)) {
            String value = normalizedText(item.asText(""));
            if (value.isBlank()) continue;
            values.add(lowerCase ? value.toLowerCase(java.util.Locale.ROOT) : value);
        }
        values = values.stream().distinct().sorted().toList();
        target.set(field, objectMapper.valueToTree(values));
    }

    private void putSortedIntegers(com.fasterxml.jackson.databind.node.ObjectNode target, JsonNode source,
                                   String field) {
        if (!source.path(field).isArray()) return;
        List<Integer> values = new ArrayList<>();
        source.path(field).forEach(value -> values.add(value.asInt()));
        target.set(field, objectMapper.valueToTree(values.stream().distinct().sorted().toList()));
    }

    private static String normalizedText(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    /**
     * Report objective progress for each requested evidence need. The Agent
     * judges semantic support after every read. Progress metadata describes the
     * observed information gain; it never closes a capability or fixes a call order.
     */
    private AgentToolExecution addEvidenceProgress(AgentToolExecution execution,
                                                   String argumentsJson,
                                                   EvidenceReadState state) {
        try {
            JsonNode parsed = objectMapper.readTree(execution.resultJson());
            if (!parsed.isObject()) return execution;
            com.fasterxml.jackson.databind.node.ObjectNode payload =
                    (com.fasterxml.jackson.databind.node.ObjectNode) parsed;
            String resultStatus = payload.path("status").asText("");
            JsonNode requestedNeeds = objectMapper.readTree(argumentsJson).path("needs");
            if ("invalid_request".equals(resultStatus)) {
                return execution;
            }
            if ("unavailable".equals(resultStatus)) {
                return execution;
            }
            JsonNode returnedNeeds = payload.path("evidenceNeeds");
            if (requestedNeeds.isArray() && !requestedNeeds.isEmpty() && returnedNeeds.isArray()) {
                Map<String, JsonNode> returnedById = new LinkedHashMap<>();
                for (JsonNode returned : returnedNeeds) {
                    String id = returned.path("needId").asText("").trim();
                    if (!id.isBlank()) returnedById.putIfAbsent(id, returned);
                }
                boolean anyNewSources = false;
                boolean allNeedsStopped = true;
                boolean sawNeedProgress = false;
                for (int index = 0; index < requestedNeeds.size(); index++) {
                    JsonNode need = requestedNeeds.get(index);
                    String id = need.path("id").asText("").trim();
                    if (id.isBlank()) id = "need-" + index;
                    JsonNode returned = returnedById.get(id);
                    if (returned == null || !returned.isObject()) {
                        allNeedsStopped = false;
                        continue;
                    }
                    String fingerprint = evidenceNeedFingerprint(need);
                    String previousFingerprint = state.lastFingerprintByNeed.get(id);
                    boolean sameRequest = fingerprint.equals(previousFingerprint);
                    int requestCount = state.requestCountsByNeed.getOrDefault(id, 0);
                    if (!sameRequest) {
                        requestCount++;
                        state.requestCountsByNeed.put(id, requestCount);
                        state.lastFingerprintByNeed.put(id, fingerprint);
                        if (requestCount > 1) {
                            state.refinementCountsByNeed.merge(id, 1, Integer::sum);
                        }
                    }
                    state.objectiveByNeed.putIfAbsent(id, normalizedText(need.path("objective").asText("")));
                    Set<String> sourceIds = new LinkedHashSet<>();
                    for (JsonNode sourceId : returned.path("sourceObjectIds")) {
                        String value = sourceId.asText("").trim();
                        if (!value.isBlank()) sourceIds.add(value);
                    }
                    Set<String> seen = state.seenSourceIdsByNeed
                            .computeIfAbsent(id, ignored -> new LinkedHashSet<>());
                    Set<String> newForNeed = new LinkedHashSet<>(sourceIds);
                    newForNeed.removeAll(seen);
                    seen.addAll(sourceIds);
                    anyNewSources |= !newForNeed.isEmpty();
                    sawNeedProgress = true;

                    String progressState;
                    String nextAction;
                    String reason;
                    boolean hasMore = returned.path("hasMore").asBoolean(false);
                    if (!newForNeed.isEmpty()) {
                        progressState = "new_sources";
                        nextAction = "judge";
                        reason = "返回了该 Need 尚未读取的候选来源；请阅读原文并判断语义充分性。";
                    } else if (sameRequest) {
                        progressState = "duplicate_request";
                        nextAction = "stop";
                        reason = "本次请求与该 Need 的上一请求在检索意义上相同。";
                    } else if (hasMore) {
                        progressState = "more_candidates";
                        nextAction = "continue";
                        reason = "当前候选页未返回完；如仍缺少具体事实，请使用返回的 nextCursor 继续读取。";
                    } else if (requestCount == 1) {
                        progressState = "no_match";
                        nextAction = "refine";
                        reason = "本次请求没有返回候选来源；如果仍需要该事实，可针对明确缺口修改检索条件。";
                    } else {
                        progressState = sourceIds.isEmpty() ? "no_match" : "same_sources";
                        nextAction = "stop";
                        reason = sourceIds.isEmpty()
                                ? "补检索仍未返回候选来源，请停止该检索方向并仅基于已读内容回答。"
                                : "补检索只返回该 Need 已读来源，请停止该检索方向并仅基于已读内容回答。";
                    }
                    com.fasterxml.jackson.databind.node.ObjectNode progress =
                            objectMapper.createObjectNode();
                    progress.put("outcome", progressState);
                    progress.put("attempt", requestCount);
                    progress.put("refinementCount", state.refinementCountsByNeed.getOrDefault(id, 0));
                    progress.put("newDistinctSources", newForNeed.size());
                    progress.set("newSourceObjectIds", objectMapper.valueToTree(newForNeed));
                    progress.put("recommendedAction", nextAction);
                    progress.put("reason", reason);
                    ((com.fasterxml.jackson.databind.node.ObjectNode) returned)
                            .set("progress", progress);
                    if (!"stop".equals(nextAction)) {
                        allNeedsStopped = false;
                    }
                }
                if (sawNeedProgress) {
                    payload.put("noProgress", !anyNewSources);
                    payload.put("stopRecommended", allNeedsStopped);
                    payload.put("stopScope", allNeedsStopped ? "all_needs" : "individual_need");
                    payload.put("stopReason", allNeedsStopped
                            ? "所有当前 Need 都没有新的可用候选，或已完成明确的补检索。"
                            : "至少有一个 Need 仍有新来源或可通过 nextCursor 继续读取。"
                    );
                }
            }
            return new AgentToolExecution(objectMapper.writeValueAsString(payload),
                    execution.sourceObjectIds(), execution.visuals());
        } catch (Exception ignored) {
            // Diagnostics must never make an otherwise valid paper read unavailable.
            return execution;
        }
    }

    /**
     * The durable timeout scanner and the provider thread can finish the same run
     * concurrently. Make the run transition the visibility boundary: a late
     * provider result must not create an assistant message after timeout won.
     */
    private boolean transitionRunBeforePersistingResult(String runId, AgentRunStatus target,
                                                        String resultJson) {
        try {
            runtimeService.transitionRun(runId, target, resultJson, null, null);
            return true;
        } catch (RuntimeException transitionFailure) {
            try {
                AgentRunRecord current = runtimeService.getRun(runId);
                if (current != null && isTerminalStatus(current.getStatus())) return false;
            } catch (RuntimeException stateReadFailure) {
                transitionFailure.addSuppressed(stateReadFailure);
            }
            throw transitionFailure;
        }
    }

    private void failLateToolCall(AgentToolCallRecord call) {
        try {
            runtimeService.transitionToolCall(call.getToolCallId(), AgentToolCallStatus.FAILED,
                    null, "RUN_NOT_ACTIVE", "agent run was finalized before this tool result");
        } catch (RuntimeException ignored) {
            // The timeout thread may have finalized the run and the tool call at the same time.
        }
    }

    private static boolean isTerminalStatus(String status) {
        try {
            return status != null && AgentRunStatus.valueOf(status).isTerminal();
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private String readUnavailableResult() {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "status", "unavailable",
                    "sources", List.of(),
                    "message", "本轮无法读取请求的论文能力。请基于对话、当前选区和已经成功加载的论文上下文继续；不要编造引用、页码、公式编号或精确数值。"
            ));
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            return "{\"status\":\"unavailable\",\"sources\":[]}";
        }
    }

    private static AgentToolExecution toolResult(String resultJson) {
        return new AgentToolExecution(resultJson, Set.of());
    }

    private AgentToolExecution idempotentToolResult(String toolName, AgentToolExecution previous,
                                                     String message) throws Exception {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("status", "already_available");
        payload.put("tool", toolName);
        payload.put("message", message);
        payload.put("newInformation", false);
        payload.set("sourceObjectIds", objectMapper.valueToTree(previous.sourceObjectIds()));
        return new AgentToolExecution(objectMapper.writeValueAsString(payload),
                previous.sourceObjectIds(), List.of());
    }

    private static boolean isStoredResult(AgentTurnResult result, String runId) {
        return result != null && runId != null && runId.equals(result.runId())
                && result.status() != null && !result.status().isBlank()
                && result.message() != null && !result.message().isBlank();
    }

    private record PlannedActionOutput(AgentToolCallRecord primaryCall,
                                       AgentToolRequest request,
                                       PaperActionSkillTool.PreparedActionPlan plan) { }

    /** Run-local output obligations; persisted tickets remain the durable action state. */
    private static final class RunOutputState {
        private boolean actionRequired;
        private PlannedActionOutput actionPlan;
        private String groundingMode = "GENERAL_KNOWLEDGE";
        private String responseMode = "CONTENT";

        synchronized void activate(String skillName) {
            if (PaperActionSkillTool.SKILL_NAME.equals(skillName)) actionRequired = true;
        }

        synchronized boolean actionRequired() { return actionRequired; }
        synchronized boolean hasActionPlan() { return actionPlan != null; }
        synchronized boolean actionOutputRequired() {
            return "ACTION_ONLY".equals(responseMode) || "CONTENT_AND_ACTION".equals(responseMode);
        }
        synchronized boolean contentRequired() {
            return "CONTENT".equals(responseMode) || "CONTENT_AND_ACTION".equals(responseMode);
        }
        synchronized String responseMode() { return responseMode; }
        synchronized boolean paperGroundingRequired() {
            return "PAPER".equals(groundingMode) || "MIXED".equals(groundingMode);
        }

        synchronized void finish(String grounding, String response) {
            if (!Set.of("PAPER", "GENERAL_KNOWLEDGE", "MIXED").contains(grounding)) {
                throw new IllegalArgumentException("groundingMode 必须是 PAPER、GENERAL_KNOWLEDGE 或 MIXED");
            }
            if (!Set.of("CONTENT", "ACTION_ONLY", "CONTENT_AND_ACTION").contains(response)) {
                throw new IllegalArgumentException("responseMode 必须是 CONTENT、ACTION_ONLY 或 CONTENT_AND_ACTION");
            }
            groundingMode = grounding;
            responseMode = response;
        }

        synchronized void markPaperEvidenceUsed() {
            if ("GENERAL_KNOWLEDGE".equals(groundingMode)) groundingMode = "PAPER";
        }

        synchronized void plan(AgentToolCallRecord call, AgentToolRequest request,
                               PaperActionSkillTool.PreparedActionPlan plan) {
            if (actionPlan != null) {
                throw new IllegalArgumentException("本轮页面操作计划已经提交；请一次列出全部操作");
            }
            actionRequired = true;
            actionPlan = new PlannedActionOutput(call, request, plan);
        }

        synchronized PlannedActionOutput requirePlan() {
            if (actionPlan == null) throw new IllegalStateException("ACTION_PLAN_REQUIRED");
            return actionPlan;
        }
    }

    private static final class EvidenceReadState {
        private boolean usableEvidence;
        private final Map<String, Set<String>> seenSourceIdsByNeed = new LinkedHashMap<>();
        private final Map<String, String> objectiveByNeed = new LinkedHashMap<>();
        private final Map<String, String> lastFingerprintByNeed = new LinkedHashMap<>();
        private final Map<String, Integer> requestCountsByNeed = new LinkedHashMap<>();
        private final Map<String, Integer> refinementCountsByNeed = new LinkedHashMap<>();
        private final Set<String> requiredFigureSourceIds = new LinkedHashSet<>();
        private final Map<String, String> sourceIdsByLabel = new LinkedHashMap<>();

        private void markUsableEvidence() {
            usableEvidence = true;
        }

        private boolean hasUsableEvidence() {
            return usableEvidence;
        }

        private void registerSources(Iterable<String> sourceIds) {
            if (sourceIds == null) return;
            for (String sourceId : sourceIds) {
                if (sourceId == null || sourceId.isBlank() || sourceIdsByLabel.containsValue(sourceId)) continue;
                sourceIdsByLabel.put("S" + (sourceIdsByLabel.size() + 1), sourceId);
            }
            if (!sourceIdsByLabel.isEmpty()) usableEvidence = true;
        }

        private String labelFor(String sourceId) {
            if (sourceId == null || sourceId.isBlank()) return null;
            return sourceIdsByLabel.entrySet().stream()
                    .filter(entry -> sourceId.equals(entry.getValue()))
                    .map(Map.Entry::getKey)
                    .findFirst().orElse(null);
        }

        private String sourceId(String label) {
            if (label == null) return null;
            return sourceIdsByLabel.get(label.toUpperCase(Locale.ROOT));
        }
    }

    void rejectPrepared(PreparedTurn prepared, RuntimeException error) {
        AgentRunRecord current = runtimeService.getRun(prepared.run().getRunId());
        if (AgentRunStatus.QUEUED.name().equals(current.getStatus())
                || AgentRunStatus.RUNNING.name().equals(current.getStatus())) {
            runtimeService.transitionRun(current.getRunId(), AgentRunStatus.FAILED, null,
                    "AGENT_DISPATCH_FAILED", safeError(error));
        }
    }

    record PreparedTurn(AgentTurnInput input, AgentContextSnapshot context,
                        AgentTurnRecord turn, AgentRunRecord run, boolean duplicateRequest) {
    }

    public AgentTurnResult currentResult(String runId) {
        AgentRunRecord run = runtimeService.getRun(runId);
        AgentTurnRecord turn = runtimeService.getTurnForRun(runId);
        if (run.getResultJson() != null) {
            try {
                return readResult(run.getResultJson());
            } catch (Exception error) {
                throw new IllegalStateException("已保存的 Agent 结果格式无效", error);
            }
        }
        ResearchMessage message = messageMapper.selectLatestAssistantByRun(runId);
        String content = message == null ? null : message.getContent();
        if ((content == null || content.isBlank()) && ("FAILED".equals(run.getStatus())
                || "CANCELLED".equals(run.getStatus()))) {
            content = failureForRun(run).userMessage();
        }
        return new AgentTurnResult(turn.getTurnId(), runId, run.getStatus(),
                content, List.of(), List.of());
    }

    private AgentRunFailureClassifier.Failure failureForRun(AgentRunRecord run) {
        if (!"RUN_TIMEOUT".equals(run.getErrorCode())) {
            return AgentRunFailureClassifier.fromCode(run.getErrorCode());
        }
        try {
            com.fasterxml.jackson.databind.JsonNode trace = run.getModelTraceJson() == null
                    || run.getModelTraceJson().isBlank() ? null : objectMapper.readTree(run.getModelTraceJson());
            return AgentRunFailureClassifier.classifyTimeoutTrace(trace, run.getMaxModelCalls());
        } catch (Exception ignored) {
            return AgentRunFailureClassifier.fromCode(run.getErrorCode());
        }
    }

    private static boolean containsDisplayMath(String text) {
        return text.contains("$$") || text.contains("\\[");
    }

    private static String omitUnsupportedDisplayMath(String text) {
        String sanitized = DISPLAY_MATH.matcher(text).replaceAll(
                "（当前已读来源不足以可靠展示该公式）");
        return sanitized.replaceAll("(?:\\s*\\n){3,}", "\n\n").trim();
    }

    private static boolean hasReliableFormulaSupport(JsonNode sourceIds, AgentContextSnapshot context,
                                                     Set<String> visuallyReadSources) {
        if (context.sourceCatalog() == null) return false;
        for (JsonNode sourceNode : sourceIds) {
            String sourceId = sourceNode.asText("").trim();
            SourceObject source = context.sourceCatalog().objects().get(sourceId);
            if (source == null || source.contentType()
                    != com.research.assistant.service.agent.source.SourceContentType.FORMULA) continue;
            boolean textReliable = Boolean.parseBoolean(
                    source.provenance().getOrDefault("textReliable", "false"));
            if (textReliable || visuallyReadSources.contains(sourceId)) return true;
        }
        return false;
    }

    private static List<String> reliableReadFormulaSources(AgentContextSnapshot context,
                                                           Set<String> readSources,
                                                           Set<String> visuallyReadSources) {
        if (context.sourceCatalog() == null || readSources == null) return List.of();
        return readSources.stream()
                .filter(sourceId -> {
                    SourceObject source = context.sourceCatalog().objects().get(sourceId);
                    if (source == null || source.contentType()
                            != com.research.assistant.service.agent.source.SourceContentType.FORMULA) return false;
                    boolean textReliable = Boolean.parseBoolean(
                            source.provenance().getOrDefault("textReliable", "false"));
                    return textReliable || visuallyReadSources.contains(sourceId);
                })
                .sorted()
                .limit(4)
                .toList();
    }

    static String stripModelCitationMarkers(String value) {
        if (value == null || value.isEmpty()) return "";
        StringBuilder output = new StringBuilder(value.length());
        boolean fencedCode = false;
        boolean inlineCode = false;
        boolean displayMath = false;
        boolean inlineMath = false;
        for (int index = 0; index < value.length();) {
            if (value.startsWith("```", index)) {
                fencedCode = !fencedCode;
                output.append("```");
                index += 3;
                continue;
            }
            char current = value.charAt(index);
            if (!fencedCode && current == '`') {
                inlineCode = !inlineCode;
                output.append(current);
                index++;
                continue;
            }
            if (!fencedCode && !inlineCode && current == '$') {
                boolean doubleDollar = index + 1 < value.length() && value.charAt(index + 1) == '$';
                if (doubleDollar) displayMath = !displayMath;
                else if (!displayMath) inlineMath = !inlineMath;
                output.append(doubleDollar ? "$$" : "$");
                index += doubleDollar ? 2 : 1;
                continue;
            }
            if (!fencedCode && !inlineCode && !displayMath && !inlineMath && current == '\\'
                    && index + 1 < value.length() && (value.charAt(index + 1) == '(' || value.charAt(index + 1) == '[')) {
                String close = value.charAt(index + 1) == '(' ? "\\)" : "\\]";
                int end = value.indexOf(close, index + 2);
                if (end >= 0) {
                    output.append(value, index, end + close.length());
                    index = end + close.length();
                    continue;
                }
            }
            if (!fencedCode && !inlineCode && !displayMath && !inlineMath && current == '[') {
                int close = value.indexOf(']', index + 1);
                if (close > index + 1) {
                    String marker = value.substring(index + 1, close);
                    if (isStandaloneCitationMarker(marker, value, index, close)) {
                        while (output.length() > 0 && Character.isWhitespace(output.charAt(output.length() - 1))) {
                            output.deleteCharAt(output.length() - 1);
                        }
                        index = close + 1;
                        continue;
                    }
                }
            }
            output.append(current);
            index++;
        }
        return output.toString();
    }

    private static boolean isStandaloneCitationMarker(String marker, String value, int start, int end) {
        String normalized = marker.replace('，', ',').replace('、', ',').replace('－', '-').trim();
        if (!normalized.matches("[1-9]\\d*(?:\\s*[,\\-]\\s*[1-9]\\d*)*")) return false;
        int previous = start - 1;
        while (previous >= 0 && Character.isWhitespace(value.charAt(previous))) previous--;
        if (previous >= 0 && "=∈∉≤≥<>+-*/^_([{".indexOf(value.charAt(previous)) >= 0) return false;
        int next = end + 1;
        while (next < value.length() && Character.isWhitespace(value.charAt(next))) next++;
        return next >= value.length() || "，。！？.!?;；:：)）]】".indexOf(value.charAt(next)) >= 0;
    }

    private void claimAttachments(AgentTurnInput input, AgentTurnRecord turn) {
        List<String> ids = new ArrayList<>(input.attachmentIds());
        ids.addAll(input.formulaAttachmentIds());
        if (ids.isEmpty()) return;
        if (attachmentService == null) throw new IllegalStateException("attachment service is unavailable");
        attachmentService.claim(turn.getId(), input.conversationId(), ids);
    }

    private AgentTurnResult executeExplicitAction(AgentTurnInput input, AgentContextSnapshot context,
                                                  AgentTurnRecord turn, AgentRunRecord run) {
        Object sourceValue = input.explicitAction().parameters().get("sourceObjectId");
        String sourceId = sourceValue == null ? null : sourceValue.toString().trim();
        String content = optionalParameter(input.explicitAction().parameters().get("content"));
        String color = optionalParameter(input.explicitAction().parameters().get("color"));
        PaperActionSkillTool.PreparedAction preparedAction = actionSkillTool.prepareExplicit(context.sourceCatalog(),
                input.explicitAction().type(), sourceId, content, color);
        String arguments;
        try { arguments = objectMapper.writeValueAsString(input.explicitAction().parameters()); }
        catch (Exception error) { throw new IllegalArgumentException("显式页面操作参数无效", error); }
        AgentToolCallRecord call = runtimeService.registerToolCall(run.getRunId(),
                "explicit_" + preparedAction.type().name().toLowerCase(), arguments, false,
                run.getRunId() + ":explicit:" + preparedAction.type() + ":" + sourceId);
        call = runtimeService.transitionToolCall(call.getToolCallId(), AgentToolCallStatus.RUNNING, null, null, null);
        ActionTicketService.IssuedActionTicket issued = ticketService.issue(run.getRunId(), call,
                preparedAction.type(), preparedAction.target(), preparedAction.content(), preparedAction.color());
        AgentPendingAction action = new AgentPendingAction(call.getToolCallId(), preparedAction.type(),
                preparedAction.target(), preparedAction.content(), preparedAction.color(),
                issued.ticket(), issued.expiresAt());
        String message = "正在执行页面操作。";
        AgentTurnResult waiting = new AgentTurnResult(turn.getTurnId(), run.getRunId(), AgentRunStatus.WAITING_CLIENT.name(),
                message, List.of(), List.of(), List.of(action));
        String resultJson = writeResult(waiting);
        if (!transitionRunBeforePersistingResult(run.getRunId(), AgentRunStatus.WAITING_CLIENT, resultJson)) {
            failLateToolCall(call);
            return currentResult(run.getRunId());
        }
        return waiting;
    }

    private static String optionalParameter(Object value) {
        if (value == null || value.toString().isBlank()) return null;
        return value.toString().trim();
    }

    private String writeResult(AgentTurnResult result) {
        try {
            return objectMapper.copy().findAndRegisterModules().writeValueAsString(result);
        } catch (Exception error) {
            throw new IllegalStateException("Agent 结果序列化失败", error);
        }
    }

    private AgentTurnResult readResult(String json) throws Exception {
        return objectMapper.copy().findAndRegisterModules().readValue(json, AgentTurnResult.class);
    }

    private ResearchMessage saveUserMessage(AgentTurnInput input, AgentTurnRecord turn, String runId) {
        ResearchMessage message = baseMessage(input.conversationId(), "USER", "CHAT", input.userMessage(), turn, runId);
        message.setMessageKey("agent-user-" + input.clientRequestId());
        if (input.selectedContent() != null) {
            try { message.setSelectionAnchorJson(objectMapper.writeValueAsString(input.selectedContent())); }
            catch (Exception error) { throw new IllegalStateException("选区序列化失败", error); }
        }
        List<String> attachmentIds = new ArrayList<>(input.attachmentIds());
        attachmentIds.addAll(input.formulaAttachmentIds());
        if (!attachmentIds.isEmpty()) {
            if (attachmentService == null) throw new IllegalStateException("attachment service is unavailable");
            try {
                var metadata = attachmentService.requireForSession(input.conversationId(), attachmentIds).stream()
                        .map(item -> java.util.Map.of(
                                "attachmentId", item.getAttachmentId(),
                                "name", item.getOriginalName() == null ? "附件" : item.getOriginalName(),
                                "mimeType", item.getMediaType(),
                                "extractionStatus", item.getExtractionStatus()))
                        .toList();
                message.setEvidenceJson(objectMapper.writeValueAsString(java.util.Map.of("attachments", metadata)));
                message.setEvidenceSchemaVersion("agent-input-v1");
            } catch (Exception error) {
                throw new IllegalStateException("附件元数据序列化失败", error);
            }
        }
        messageMapper.insert(message);
        return message;
    }

    private void saveAssistantMessage(AgentTurnRecord turn, String runId, String type, String content, String evidenceJson) {
        ResearchMessage message = baseMessage(turn.getSessionId(), "ASSISTANT", type, content, turn, runId);
        message.setMessageKey("agent-assistant-" + UUID.randomUUID());
        message.setEvidenceJson(evidenceJson);
        message.setEvidenceSchemaVersion(evidenceJson == null ? null : "ground-evidence-v2");
        messageMapper.insert(message);
        if (!"CLARIFICATION".equals(type)) runtimeService.bindFinalMessage(turn.getTurnId(), message.getMessageKey());
        if ("CHAT".equals(type) && summaryService != null) {
            try { summaryService.scheduleIfNeeded(turn.getSessionId()); }
            catch (RuntimeException ignored) { /* summary failure never changes a completed answer */ }
        }
    }

    private ResearchMessage baseMessage(long sessionId, String role, String type, String content,
                                        AgentTurnRecord turn, String runId) {
        ResearchMessage message = new ResearchMessage();
        message.setSessionId(sessionId);
        message.setRole(role);
        message.setMessageType(type);
        message.setMessageStatus("FINAL");
        message.setContent(content == null ? "" : content);
        message.setRunId(runId);
        message.setAgentTurnId(turn.getId());
        return message;
    }

    private static String requiredText(JsonNode root, String name) {
        String value = root.path(name).asText("").trim();
        if (value.isEmpty()) throw new IllegalArgumentException(name + " 不能为空");
        return value;
    }

    /**
     * Jackson legitimately decodes JSON unicode escapes such as \\u0005. Such
     * escapes are valid JSON, but the resulting C0/C1 characters are not valid
     * user-visible Markdown and can break the formula renderer. Reject them at
     * the terminal tool boundary so the framework can return a tool error to the
     * model or clarification from corrupting the rendered conversation.
     */
    private static void validateModelVisibleText(String value, String field) {
        if (value == null || value.isEmpty()) return;
        for (int index = 0; index < value.length();) {
            int codePoint = value.codePointAt(index);
            boolean allowedWhitespace = codePoint == '\t' || codePoint == '\n' || codePoint == '\r';
            if (Character.isISOControl(codePoint) && !allowedWhitespace) {
                throw new IllegalArgumentException(field + " 包含不可渲染的 Unicode 控制字符 U+"
                        + String.format(Locale.ROOT, "%04X", codePoint)
                        + "；请重新生成文本，使用合法 JSON 转义并确保 LaTeX 内容可正常显示。");
            }
            index += Character.charCount(codePoint);
        }
    }

    private static List<AgentEvidenceView> evidenceViews(GroundedAnswer grounded, AgentContextSnapshot context) {
        if (context.sourceCatalog() == null) return List.of();
        return grounded.evidenceEntries().stream().map(binding -> {
            SourceObject source = context.sourceCatalog().requireObject(binding.sourceObjectId());
            List<com.research.assistant.service.agent.source.SourceLocator> locators =
                    context.sourceCatalog().requireLocators(binding.sourceObjectId());
            String evidenceKey = SourceEvidenceIdentity.key(source, locators);
            String textFormat = source.provenance().getOrDefault("textFormat",
                    source.contentType() == com.research.assistant.service.agent.source.SourceContentType.FORMULA
                            ? "PLAIN_TEXT" : "PLAIN_TEXT");
            boolean textReliable = Boolean.parseBoolean(source.provenance().getOrDefault("textReliable",
                    source.contentType() != com.research.assistant.service.agent.source.SourceContentType.FORMULA
                            && !"VISUAL_FALLBACK".equals(source.provenance().get("recoveryMode"))
                            ? "true" : "false"));
            String fullText = source.rawContent();
            if (!textReliable && source.contentType()
                    == com.research.assistant.service.agent.source.SourceContentType.FORMULA) {
                fullText = source.formulaNumber().isBlank()
                        ? "公式区域" : "公式 (" + source.formulaNumber() + ")";
            }
            return new AgentEvidenceView(binding.citationNumber(), binding.sourceObjectId(), context.paperId(),
                    binding.quote(), fullText, evidenceKey, source.contentType().name(), textFormat,
                    textReliable, source.formulaNumber(), locators);
        }).toList();
    }

    private String canonicalArguments(String argumentsJson) {
        try {
            return objectMapper.writeValueAsString(canonicalNode(objectMapper.readTree(argumentsJson)));
        } catch (Exception ignored) {
            return argumentsJson == null ? "" : argumentsJson.trim();
        }
    }

    private JsonNode canonicalNode(JsonNode node) {
        if (node == null || node.isNull() || node.isValueNode()) return node;
        if (node.isArray()) {
            com.fasterxml.jackson.databind.node.ArrayNode result = objectMapper.createArrayNode();
            node.forEach(value -> result.add(canonicalNode(value)));
            return result;
        }
        com.fasterxml.jackson.databind.node.ObjectNode result = objectMapper.createObjectNode();
        List<String> fields = new ArrayList<>();
        node.fieldNames().forEachRemaining(fields::add);
        fields.stream().sorted().forEach(field -> result.set(field, canonicalNode(node.get(field))));
        return result;
    }

    private static String safeError(Exception error) {
        String value = error.getMessage();
        if (value == null || value.isBlank()) value = error.getClass().getSimpleName();
        return value.length() > 900 ? value.substring(0, 900) : value;
    }

}
