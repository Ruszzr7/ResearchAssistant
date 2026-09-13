package com.research.assistant.service.agent.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.LinkedHashSet;

@Service
public class AgentLoopService {
    private static final int MAX_EFFECTIVE_EVIDENCE_ATTEMPTS_PER_NEED = 3;
    private static final int MAX_EFFECTIVE_EVIDENCE_CALLS_PER_RUN = 3;

    // Interactive paper answers retain a final liveness boundary if a provider call stalls.
    // Normal Agent decisions are not constrained by a tool-round workflow budget.
    private static final AgentRunBudget DEFAULT_BUDGET = AgentRunBudget.defaults();
    private static final String ANSWER_SCHEMA = """
            {"type":"object","properties":{
            "groundingMode":{"type":"string","enum":["PAPER","GENERAL_KNOWLEDGE","MIXED"],"description":"PAPER 表示回答依赖当前论文；GENERAL_KNOWLEDGE 表示完全不依赖论文；MIXED 表示同时包含两者。"},
            "answerBlocks":{"type":"array","items":{"type":"object","properties":{
            "text":{"type":"string","description":"完整的 GitHub 风格 Markdown。数学使用 $...$ 或 $$...$$；不要添加数字引用标记。只写已由对应来源支持的内容，无法确认的细节直接省略。"},"sourceObjectIds":{"type":"array","description":"支持本答案块的、且已在当前论文版本上下文中实际读取的来源 ID；不依赖论文原文的回答可填写空数组。","items":{"type":"string"}}},
            "required":["text","sourceObjectIds"],"additionalProperties":false}},
            "clarification":{"type":"string","description":"仅当请求确实存在歧义时提出一个简短的中文澄清问题；此时留空或省略 answerBlocks。"}},
            "required":["groundingMode"],"additionalProperties":false}
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
        List<AgentToolDefinition> definitions = new ArrayList<>();
        List<AgentSkillBinding> skills = skillRegistry.bindings(context, input.userMessage());
        definitions.add(new AgentToolDefinition("submit_answer",
                "所有正常回答都必须使用本工具提交。先如实声明 groundingMode：当前论文的内容、方法、创新、公式、图表或结论属于 PAPER；完全不依赖论文的知识属于 GENERAL_KNOWLEDGE；两者并存时使用 MIXED。使用有序的 answerBlocks 提交最终答案；PAPER 回答的每个块都必须绑定实际读取且支持该块的 sourceObjectIds，MIXED 中只有完全不依赖论文的块可以使用空数组。包含 $$...$$ 或 \\[...\\] 完整展示公式的论文答案块，必须绑定可靠公式文本或已实际读取图像的公式来源。每个块只放一个事实性陈述或一组紧密相关的陈述。画像、摘要或一次未命中都不能代替原文证据；只写已确认的内容，不要输出检索过程、来源说明或内部诊断段落。", ANSWER_SCHEMA));
        definitions.add(new AgentToolDefinition("ask_clarification",
                "仅当页面操作或答案实质依赖缺失或含糊的用户意图时，提出一个简短的自然语言澄清问题。普通可解问题不要使用。",
                CLARIFICATION_SCHEMA));
        List<AgentChatEntry> messages = context.messages();
        try {
            AgentFrameworkResult frameworkResult = frameworkExecutor.execute(messages, definitions, skills,
                    request -> executeFrameworkTool(context, turn, run.getRunId(), readSources,
                            visuallyReadSources, readToolCache, evidenceReadState, input.userMessage(), request),
                    (toolCallId, skillName, argumentsJson, instructions) -> persistSkillActivation(
                            run.getRunId(), toolCallId, skillName, argumentsJson, instructions),
                    trace -> runtimeService.recordModelCall(run.getRunId(), trace));
            runtimeService.recordUsage(run.getRunId(), frameworkResult.modelCalls(), frameworkResult.toolCalls(),
                    frameworkResult.promptTokens(), frameworkResult.completionTokens());
            if (frameworkResult.content() != null && !frameworkResult.content().isBlank()) {
                try {
                    AgentTurnResult structured = readResult(frameworkResult.content());
                    if (isStoredResult(structured, run.getRunId())) return structured;
                } catch (com.fasterxml.jackson.core.JsonProcessingException notStructured) {
                    if (evidenceReadState.hasUsableEvidence()) {
                        throw new IllegalStateException("GROUNDING_SUBMISSION_REQUIRED：已读取论文证据，最终答案必须使用 submit_answer");
                    }
                }
                // 即使是通用问题也必须走 submit_answer，统一保存终态并避免模型
                // 绕过结构化校验直接写入对话历史。
                throw new IllegalStateException("ANSWER_SUBMISSION_REQUIRED：最终答案必须使用 submit_answer");
            }
            throw new IllegalStateException("模型返回了空响应");
        } catch (AgentFrameworkExecutionException failure) {
            AgentFrameworkResult usage = failure.usage();
            runtimeService.recordUsage(run.getRunId(), usage.modelCalls(), usage.toolCalls(),
                    usage.promptTokens(), usage.completionTokens());
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
                                        String evidenceFocus,
                                        AgentToolRequest request) {
        boolean mutation = actionSkillTool.supports(request.name());
        AgentToolCallRecord call = runtimeService.registerToolCall(runId, request.name(),
                request.argumentsJson(), !mutation, runId + ":" + request.id());
        call = runtimeService.transitionToolCall(call.getToolCallId(), AgentToolCallStatus.RUNNING,
                null, null, null);
        try {
            if ("ask_clarification".equals(request.name())) {
                String question = requiredText(objectMapper.readTree(request.argumentsJson()), "question");
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
            if ("submit_answer".equals(request.name())) {
                JsonNode submission = objectMapper.readTree(request.argumentsJson());
                String clarification = optionalParameter(submission.path("clarification").asText(null));
                JsonNode submittedBlocks = submission.path("answerBlocks");
                if (clarification != null && (!submittedBlocks.isArray() || submittedBlocks.isEmpty())) {
                    AgentTurnResult waiting = new AgentTurnResult(turn.getTurnId(), runId,
                            AgentRunStatus.WAITING_USER.name(), clarification, List.of(), List.of());
                    String resultJson = writeResult(waiting);
                    if (!transitionRunBeforePersistingResult(runId, AgentRunStatus.WAITING_USER, resultJson)) {
                        failLateToolCall(call);
                        return toolResult(writeResult(currentResult(runId)));
                    }
                    runtimeService.transitionToolCall(call.getToolCallId(), AgentToolCallStatus.COMPLETED,
                            resultJson, null, null);
                    saveAssistantMessage(turn, runId, "CLARIFICATION", clarification, null);
                    return toolResult(resultJson);
                }
                GroundedAnswer grounded = parseAndGround(request.argumentsJson(), context, readSources,
                        visuallyReadSources, evidenceReadState.requiredFigureSourceIds);
                List<AgentEvidenceView> evidence = evidenceViews(grounded, context);
                AgentTurnResult completed = new AgentTurnResult(turn.getTurnId(), runId,
                        AgentRunStatus.COMPLETED.name(), grounded.answer(), grounded.bindings(), evidence);
                String resultJson = writeResult(completed);
                if (!transitionRunBeforePersistingResult(runId, AgentRunStatus.COMPLETED, resultJson)) {
                    failLateToolCall(call);
                    return toolResult(writeResult(currentResult(runId)));
                }
                runtimeService.transitionToolCall(call.getToolCallId(), AgentToolCallStatus.COMPLETED,
                        resultJson, null, null);
                saveAssistantMessage(turn, runId, "CHAT", grounded.answer(), resultJson);
                return toolResult(resultJson);
            }
            if (actionSkillTool.supports(request.name())) {
                List<PaperActionSkillTool.PreparedAction> preparedActions = actionSkillTool.prepare(
                        context.sourceCatalog(), readSources, request.argumentsJson(), objectMapper);
                List<AgentPendingAction> actions = new ArrayList<>();
                for (int targetIndex = 0; targetIndex < preparedActions.size(); targetIndex++) {
                    PaperActionSkillTool.PreparedAction preparedAction = preparedActions.get(targetIndex);
                    AgentToolCallRecord actionCall = call;
                    if (targetIndex > 0) {
                        // One model action may name several explicit sources. Keep one
                        // ticket/tool-call per physical target so the existing annotation
                        // idempotency key and receipt protocol remain atomic per target.
                        String targetArguments = actionArgumentsForTarget(request.argumentsJson(), preparedAction);
                        actionCall = runtimeService.registerToolCall(runId, request.name(), targetArguments,
                                false, runId + ":" + request.id() + ":target:" + targetIndex);
                        actionCall = runtimeService.transitionToolCall(actionCall.getToolCallId(),
                                AgentToolCallStatus.RUNNING, null, null, null);
                    }
                    ActionTicketService.IssuedActionTicket issued = ticketService.issue(runId, actionCall,
                            preparedAction.type(), preparedAction.target(), preparedAction.content(), preparedAction.color());
                    actions.add(new AgentPendingAction(actionCall.getToolCallId(), preparedAction.type(),
                            preparedAction.target(), preparedAction.content(), preparedAction.color(),
                            issued.ticket(), issued.expiresAt()));
                }
                AgentTurnResult waiting = new AgentTurnResult(turn.getTurnId(), runId,
                        AgentRunStatus.WAITING_CLIENT.name(), "正在执行页面操作。", List.of(), List.of(), actions);
                String resultJson = writeResult(waiting);
                if (!transitionRunBeforePersistingResult(runId, AgentRunStatus.WAITING_CLIENT, resultJson)) {
                    failLateToolCall(call);
                    return toolResult(writeResult(currentResult(runId)));
                }
                // ActionTicketService.issue atomically stores the ticket and moves the
                // tool call to WAITING_CLIENT.  Repeating the same transition here makes
                // the state machine reject the call and leaves a WAITING_CLIENT run with
                // a FAILED tool call, which can never accept the browser receipt.
                return toolResult(resultJson);
            }
            if (profileSkillTool != null && profileSkillTool.supports(request.name())) {
                if (context.paperId() == null) {
                    throw new IllegalArgumentException("论文画像不可用");
                }
                AgentToolExecution overview = profileSkillTool.execute(context.paperId(), context.sourceCatalog());
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
            AgentToolExecution continuationError = "retrieve_paper_evidence".equals(request.name())
                    ? validateEvidenceContinuation(effectiveArguments, evidenceReadState) : null;
            if (continuationError != null) {
                result = continuationError;
            } else {
                synchronized (readToolCache) {
                    result = readToolCache.get(cacheKey);
                    if (result == null) {
                        evidenceReadState.effectiveEvidenceCalls++;
                        result = evidenceSkillTool.execute(context.sourceCatalog(), request.name(), effectiveArguments);
                        readToolCache.put(cacheKey, result);
                    }
                }
            }
            if (evidenceSkillTool.supports(request.name())) {
                if (!result.sourceObjectIds().isEmpty()) {
                    evidenceReadState.markUsableEvidence();
                }
                result = addEvidenceProgress(result, effectiveArguments, evidenceReadState);
            }
            readSources.addAll(result.sourceObjectIds());
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

    private AgentToolExecution validateEvidenceContinuation(String argumentsJson, EvidenceReadState state) {
        try {
            JsonNode needs = objectMapper.readTree(argumentsJson).path("needs");
            if (!needs.isArray()) return null;
            if (state.effectiveEvidenceCalls >= MAX_EFFECTIVE_EVIDENCE_CALLS_PER_RUN) {
                List<String> ids = new ArrayList<>();
                needs.forEach(need -> {
                    String id = need.path("id").asText("").trim();
                    if (!id.isBlank()) ids.add(id);
                });
                state.stoppedNeedIds.addAll(ids);
                return stoppedEvidenceResult(ids, "本轮证据检索已达到三次，请基于已读来源回答。", List.of());
            }
            List<Map<String, Object>> issues = new ArrayList<>();
            List<String> requestedNeedIds = new ArrayList<>();
            for (int index = 0; index < needs.size(); index++) {
                JsonNode need = needs.get(index);
                if (!need.isObject()) continue;
                String id = need.path("id").asText("").trim();
                String objective = normalizedText(need.path("objective").asText(""));
                if (id.isBlank() || objective.isBlank()) continue;
                requestedNeedIds.add(id);
                if (state.stoppedNeedIds.contains(id)) continue;
                String previousObjective = state.objectiveByNeed.get(id);
                if (previousObjective == null) continue;
                if (!previousObjective.equals(objective)) {
                    issues.add(evidenceIssue(id, "objective", "NEED_OBJECTIVE_CHANGED",
                            "同一 Need ID 的 objective 必须保持不变；新的事实需求请使用新的 ID。"));
                    continue;
                }
                String fingerprint = evidenceNeedFingerprint(need);
                String previousFingerprint = state.lastFingerprintByNeed.get(id);
                if (previousFingerprint != null && !previousFingerprint.equals(fingerprint)
                        && need.path("refinementReason").asText("").trim().isBlank()) {
                    issues.add(evidenceIssue(id, "refinementReason", "MISSING_REFINEMENT_REASON",
                            "补检索必须说明上一批来源还缺少什么，以及新条件为何可能得到不同证据。"));
                } else {
                    state.validationFailureCountsByNeed.put(id, 0);
                }
            }
            List<String> newNeedIds = requestedNeedIds.stream()
                    .filter(id -> !state.plannedNeedIds.isEmpty() && !state.plannedNeedIds.contains(id))
                    .distinct()
                    .toList();
            if (!newNeedIds.isEmpty()) {
                state.newNeedViolationCount++;
                List<Map<String, Object>> newNeedIssues = newNeedIds.stream()
                        .map(id -> evidenceIssue(id, "id", "NEW_NEED_NOT_ALLOWED",
                                "首次有效检索后不得用新 ID 重述原需求；请沿用原 ID 和 objective，填写 refinementReason，并修改有效检索条件。"))
                        .toList();
                if (state.newNeedViolationCount >= 2) {
                    return stoppedEvidenceResult(newNeedIds,
                            "连续两次新建未规划 Need；本次未执行底层检索。", newNeedIssues);
                }
                return toolResult(objectMapper.writeValueAsString(Map.of(
                        "status", "invalid_request",
                        "sources", List.of(),
                        "evidenceNeeds", List.of(),
                        "issues", newNeedIssues,
                        "usage", "请修正一次：沿用首次计划中的 Need ID 和 objective 做补检索，不要新建同方向 Need。"
                )));
            }
            List<String> repeatedlyInvalidNeedIds = requestedNeedIds.stream()
                    .filter(id -> state.validationFailureCountsByNeed.getOrDefault(id, 0) >= 2)
                    .distinct()
                    .toList();
            if (!repeatedlyInvalidNeedIds.isEmpty()) {
                state.stoppedNeedIds.addAll(repeatedlyInvalidNeedIds);
                return stoppedEvidenceResult(repeatedlyInvalidNeedIds,
                        "该 Need 连续两次违反补检索契约；本次未执行底层检索。", issues);
            }
            List<String> exhaustedNeedIds = requestedNeedIds.stream()
                    .filter(id -> state.requestCountsByNeed.getOrDefault(id, 0)
                            >= MAX_EFFECTIVE_EVIDENCE_ATTEMPTS_PER_NEED)
                    .distinct()
                    .toList();
            if (!exhaustedNeedIds.isEmpty()) {
                state.stoppedNeedIds.addAll(exhaustedNeedIds);
                return stoppedEvidenceResult(exhaustedNeedIds,
                        "该 Need 已完成首次检索和最多两次有效补检索；本次未再扫描更多候选。",
                        List.of());
            }
            if (issues.isEmpty() && !requestedNeedIds.isEmpty()
                    && requestedNeedIds.stream().allMatch(state.stoppedNeedIds::contains)) {
                return stoppedEvidenceResult(requestedNeedIds,
                        "该 Need 已收到停止信号；本次未再次执行检索，请仅基于已读证据回答，不要输出检索状态说明。",
                        List.of());
            }
            if (issues.isEmpty()) return null;
            return toolResult(objectMapper.writeValueAsString(Map.of(
                    "status", "invalid_request",
                    "sources", List.of(),
                    "evidenceNeeds", List.of(),
                    "issues", issues,
                    "usage", "请根据 issues 修正证据需求后再调用；不要把输入错误解释为论文没有证据。"
            )));
        } catch (Exception ignored) {
            // Static request validation in PaperReadToolRegistry owns malformed JSON and fields.
            return null;
        }
    }

    private Map<String, Object> evidenceIssue(String needId, String field, String code, String message) {
        Map<String, Object> issue = new LinkedHashMap<>();
        issue.put("needId", needId);
        issue.put("field", field);
        issue.put("code", code);
        issue.put("message", message);
        return issue;
    }

    private AgentToolExecution stoppedEvidenceResult(List<String> needIds, String reason,
                                                      List<Map<String, Object>> issues) throws Exception {
        List<Map<String, Object>> stoppedNeeds = needIds.stream().distinct()
                .map(id -> Map.<String, Object>of(
                        "needId", id,
                        "retrievalStatus", "stopped",
                        "sourceObjectIds", List.of(),
                        "progress", Map.of(
                                "outcome", "need_stopped",
                                "recommendedAction", "answer",
                                "reason", reason
                        )))
                .toList();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", "need_stopped");
        payload.put("sources", List.of());
        payload.put("evidenceNeeds", stoppedNeeds);
        if (!issues.isEmpty()) payload.put("issues", issues);
        payload.put("usage", "不得继续改写或新建同方向 Need；现在使用 submit_answer，仅提交已读证据支持的内容。");
        return toolResult(objectMapper.writeValueAsString(payload));
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
     * Report objective progress for each requested evidence need. This is a
     * liveness hint for the model, not a paper workflow gate: the Agent still
     * reads the returned sources and decides whether they support the answer.
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
                Set<String> invalidNeedIds = new LinkedHashSet<>();
                for (JsonNode issue : payload.path("issues")) {
                    String needId = issue.path("needId").asText("").trim();
                    if (!needId.isBlank()) invalidNeedIds.add(needId);
                }
                if (invalidNeedIds.isEmpty() && requestedNeeds.isArray()) {
                    for (JsonNode need : requestedNeeds) {
                        String id = need.path("id").asText("").trim();
                        if (!id.isBlank()) invalidNeedIds.add(id);
                    }
                }
                invalidNeedIds.forEach(id -> state.validationFailureCountsByNeed.merge(id, 1, Integer::sum));
                List<String> repeatedlyInvalid = invalidNeedIds.stream()
                        .filter(id -> state.validationFailureCountsByNeed.getOrDefault(id, 0) >= 2)
                        .toList();
                if (!repeatedlyInvalid.isEmpty()) {
                    state.stoppedNeedIds.addAll(repeatedlyInvalid);
                    List<Map<String, Object>> issues = new ArrayList<>();
                    for (JsonNode issue : payload.path("issues")) {
                        issues.add(objectMapper.convertValue(issue,
                                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { }));
                    }
                    return stoppedEvidenceResult(repeatedlyInvalid,
                            "该 Need 连续两次未通过输入契约；本次未执行底层检索。", issues);
                }
                return execution;
            }
            if ("unavailable".equals(resultStatus) || "need_stopped".equals(resultStatus)) {
                return execution;
            }
            JsonNode returnedNeeds = payload.path("evidenceNeeds");
            if (requestedNeeds.isArray() && !requestedNeeds.isEmpty() && returnedNeeds.isArray()) {
                if (state.plannedNeedIds.isEmpty()) {
                    for (JsonNode need : requestedNeeds) {
                        String id = need.path("id").asText("").trim();
                        if (!id.isBlank()) state.plannedNeedIds.add(id);
                    }
                }
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
                        nextAction = "refine_once";
                        reason = "第一次请求没有返回候选来源；可针对明确缺口有效改写一次。";
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
                    progress.set("newSourceObjectIds", objectMapper.valueToTree(newForNeed));
                    progress.put("recommendedAction", nextAction);
                    progress.put("reason", reason);
                    ((com.fasterxml.jackson.databind.node.ObjectNode) returned)
                            .set("progress", progress);
                    if ("stop".equals(nextAction)) {
                        state.stoppedNeedIds.add(id);
                    } else {
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

    private static boolean isStoredResult(AgentTurnResult result, String runId) {
        return result != null && runId != null && runId.equals(result.runId())
                && result.status() != null && !result.status().isBlank()
                && result.message() != null && !result.message().isBlank();
    }

    private static final class EvidenceReadState {
        private boolean usableEvidence;
        private final Map<String, Set<String>> seenSourceIdsByNeed = new LinkedHashMap<>();
        private final Map<String, String> objectiveByNeed = new LinkedHashMap<>();
        private final Map<String, String> lastFingerprintByNeed = new LinkedHashMap<>();
        private final Map<String, Integer> requestCountsByNeed = new LinkedHashMap<>();
        private final Map<String, Integer> refinementCountsByNeed = new LinkedHashMap<>();
        private final Map<String, Integer> validationFailureCountsByNeed = new LinkedHashMap<>();
        private final Set<String> plannedNeedIds = new LinkedHashSet<>();
        private final Set<String> stoppedNeedIds = new LinkedHashSet<>();
        private final Set<String> requiredFigureSourceIds = new LinkedHashSet<>();
        private int newNeedViolationCount;
        private int effectiveEvidenceCalls;

        private void markUsableEvidence() {
            usableEvidence = true;
        }

        private boolean hasUsableEvidence() {
            return usableEvidence;
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
            content = AgentRunFailureClassifier.userMessage(run.getErrorCode());
        }
        return new AgentTurnResult(turn.getTurnId(), runId, run.getStatus(),
                content, List.of(), List.of());
    }

    private GroundedAnswer parseAndGround(String json, AgentContextSnapshot context, Set<String> readSources,
                                          Set<String> visuallyReadSources,
                                          Set<String> requiredFigureSourceIds) throws Exception {
        JsonNode root = objectMapper.readTree(json);
        String groundingMode = requiredText(root, "groundingMode");
        if (!Set.of("PAPER", "GENERAL_KNOWLEDGE", "MIXED").contains(groundingMode)) {
            throw new IllegalArgumentException("groundingMode 必须是 PAPER、GENERAL_KNOWLEDGE 或 MIXED");
        }
        JsonNode blocks = root.path("answerBlocks");
        if (!blocks.isArray() || blocks.isEmpty()) throw new IllegalArgumentException("answerBlocks 不能为空");
        StringBuilder answer = new StringBuilder();
        List<CitationRequest> requests = new ArrayList<>();
        for (JsonNode block : blocks) {
            String text = stripModelCitationMarkers(requiredText(block, "text"));
            if (answer.length() > 0) answer.append("\n\n");
            int start = answer.length();
            answer.append(text);
            int end = answer.length();
            JsonNode sourceIds = block.path("sourceObjectIds");
            if (!sourceIds.isArray()) throw new IllegalArgumentException("sourceObjectIds 必须是数组");
            if ("PAPER".equals(groundingMode) && sourceIds.isEmpty()) {
                throw new IllegalArgumentException("PAPER 回答的每个答案块都必须绑定已读取的论文来源");
            }
            if ("GENERAL_KNOWLEDGE".equals(groundingMode) && !sourceIds.isEmpty()) {
                throw new IllegalArgumentException("GENERAL_KNOWLEDGE 回答不应绑定论文来源");
            }
            for (JsonNode sourceNode : sourceIds) {
                String sourceId = sourceNode.asText("").trim();
                if (sourceId.isEmpty()) throw new IllegalArgumentException("sourceObjectId 不能为空");
                if (!readSources.contains(sourceId)) {
                    throw new IllegalArgumentException("引用来源尚未在当前论文版本上下文中读取：" + sourceId);
                }
                requests.add(new CitationRequest(start, end, sourceId, null, List.of()));
            }
            if (!sourceIds.isEmpty() && containsDisplayMath(text)
                    && !hasReliableFormulaSupport(sourceIds, context, visuallyReadSources)) {
                throw new IllegalArgumentException(
                        "包含完整展示公式的论文答案块必须绑定可靠公式文本，或绑定已实际读取图像的公式来源");
            }
        }
        if ("MIXED".equals(groundingMode) && requests.isEmpty()) {
            throw new IllegalArgumentException("MIXED 回答至少需要一个论文来源");
        }
        if (!requiredFigureSourceIds.isEmpty() && requests.stream()
                .noneMatch(request -> requiredFigureSourceIds.contains(request.sourceObjectId()))) {
            throw new IllegalArgumentException(
                    "已读取实际图像的论文回答必须绑定至少一条本轮 FIGURE 视觉来源");
        }
        if (requests.isEmpty()) return new GroundedAnswer(answer.toString(), List.of(), List.of());
        if (context.sourceCatalog() == null) throw new IllegalArgumentException("论文来源不可用");
        return evidenceService.ground(answer.toString(), requests, context.sourceCatalog());
    }

    private static boolean containsDisplayMath(String text) {
        return text.contains("$$") || text.contains("\\[");
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
