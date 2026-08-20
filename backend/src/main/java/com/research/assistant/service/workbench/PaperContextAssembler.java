package com.research.assistant.service.workbench;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.entity.PaperMemoryRecord;
import com.research.assistant.mapper.PaperMemoryMapper;
import com.research.assistant.service.memory.PaperConversationTurn;
import com.research.assistant.service.memory.PaperGlobalProfile;
import com.research.assistant.service.memory.PaperMemoryClaim;
import com.research.assistant.service.memory.PaperMemoryObservation;
import com.research.assistant.service.memory.PaperMemoryObservationService;
import com.research.assistant.service.memory.PaperStructure;
import com.research.assistant.service.pdf.layout.SelectionAnchor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Applies the upper-level source, priority and compression policy for selection Q&A. */
@Service
public class PaperContextAssembler {

    static final int MAX_CONTEXT_CHARACTERS = 8_000;
    static final int MAX_SELECTION_CHARACTERS = 1_600;
    static final int MAX_CONVERSATION_CHARACTERS = 2_800;
    static final int MAX_OBSERVATION_CHARACTERS = 2_000;
    static final int MAX_PROFILE_CHARACTERS = 1_600;
    static final int MAX_ATTACHMENT_CHARACTERS = 2_400;

    private static final List<String> SOURCE_PRIORITY = List.of(
            "CURRENT_QUESTION",
            "CURRENT_SELECTION_EVIDENCE",
            "CURRENT_RETRIEVED_EVIDENCE",
            "CURRENT_USER_ATTACHMENTS",
            "SERVER_CONVERSATION_HISTORY",
            "CURRENT_VERSION_PAPER_PROFILE",
            "CURRENT_VERSION_GROUNDED_OBSERVATIONS");

    private final PaperMemoryMapper memoryMapper;
    private final PaperMemoryObservationService observationService;
    private final WorkbenchRunTraceService traceService;
    private final ObjectMapper objectMapper;
    private final TurnRoutingManager turnRoutingManager;

    public PaperContextAssembler(PaperMemoryMapper memoryMapper,
                                 PaperMemoryObservationService observationService,
                                 WorkbenchRunTraceService traceService,
                                 ObjectMapper objectMapper) {
        this(memoryMapper, observationService, traceService, objectMapper,
                new WorkbenchRetrievalPlanner(), new WorkbenchCommandPlanner(), null);
    }

    @Autowired
    public PaperContextAssembler(PaperMemoryMapper memoryMapper,
                                 PaperMemoryObservationService observationService,
                                 WorkbenchRunTraceService traceService,
                                 ObjectMapper objectMapper,
                                 WorkbenchRetrievalPlanner retrievalPlanner,
                                 WorkbenchCommandPlanner commandPlanner,
                                 TurnRoutingManager turnRoutingManager) {
        this.memoryMapper = memoryMapper;
        this.observationService = observationService;
        this.traceService = traceService;
        this.objectMapper = objectMapper;
        this.turnRoutingManager = turnRoutingManager == null
                ? new TurnRoutingManager(commandPlanner, retrievalPlanner)
                : turnRoutingManager;
    }

    public PaperContextSnapshot assemble(WorkbenchRunTrace trace, SelectionAnchor anchor) {
        if (trace.invocation().paperIds().size() != 1 || trace.artifactVersions().size() != 1) {
            throw new IllegalArgumentException("选区上下文只支持单篇论文");
        }
        PaperContextSnapshot cached = traceService.readContextSnapshot(
                trace.runId(), PaperContextSnapshot.class);
        if (cached != null && cached.matches(trace, anchor)) return cached;
        long paperId = trace.invocation().paperIds().get(0);
        WorkbenchPlan.ArtifactVersion version = trace.artifactVersions().get(0);
        String selected = bounded(anchor == null ? "" : anchor.anchorText(), MAX_SELECTION_CHARACTERS);
        BoundedText attachments = attachmentContext(trace.invocation().attachments());
        boolean truncated = anchor != null && anchor.anchorText() != null
                && anchor.anchorText().trim().length() > selected.length();
        truncated |= attachments.truncated();

        BoundedText profile = profileContext(paperId, version);
        truncated |= profile.truncated();

        List<PaperConversationTurn> storedTurns = observationService.recentConversation(
                paperId, trace.invocation().conversationId(), version.documentHash(),
                version.parserVersion(), 8);
        WorkbenchTurnRoute turnRoute = turnRoutingManager.route(
                trace.invocation(), storedTurns, profile.value());
        WorkbenchConversationRelation conversationRelation = turnRoute.conversationRelation();
        // A chat always keeps bounded same-version history. The relation only decides whether
        // retrieval reuses the previous evidence focus.
        BudgetedTurns turns = conversationItems(storedTurns);
        truncated |= turns.truncated();

        String retrievalSeed = trace.invocation().question() + "\n" + selected + "\n" + attachments.value();
        List<PaperMemoryObservation> storedObservations = observationService.relevantObservations(
                paperId, version.documentHash(), version.parserVersion(), retrievalSeed,
                trace.invocation().conversationId(), 8);
        BudgetedObservations observations = observationItems(storedObservations);
        truncated |= observations.truncated();

        PaperContextSnapshot snapshot = new PaperContextSnapshot(
                PaperContextSnapshot.SCHEMA_VERSION, paperId, version.documentHash(),
                version.parserVersion(), trace.invocation().conversationId(),
                trace.invocation().question(), selected, attachments.value(),
                anchor == null ? List.of() : anchor.blockIds(),
                PaperContextSnapshot.selectionFingerprint(anchor), profile.value(),
                turns.items(), conversationRelation, turnRoute, observations.items(), SOURCE_PRIORITY,
                new PaperContextSnapshot.Budget(
                        MAX_CONTEXT_CHARACTERS, selected.length() + attachments.value().length(), turns.characters(),
                        observations.characters(), profile.value().length()),
                truncated, Instant.now());
        traceService.saveContextSnapshot(trace.runId(), snapshot.schemaVersion(), snapshot);
        return snapshot;
    }

    private BoundedText attachmentContext(List<WorkbenchAttachment> attachments) {
        if (attachments == null || attachments.isEmpty()) return new BoundedText("", false);
        StringBuilder value = new StringBuilder();
        boolean truncated = false;
        for (WorkbenchAttachment attachment : attachments) {
            if (attachment == null || attachment.content().isBlank()) continue;
            String header = "附件「" + attachment.name() + "」：\n";
            int remaining = MAX_ATTACHMENT_CHARACTERS - value.length();
            if (remaining <= header.length()) {
                truncated = true;
                break;
            }
            if (value.length() > 0) value.append("\n");
            value.append(header);
            remaining = MAX_ATTACHMENT_CHARACTERS - value.length();
            String content = bounded(attachment.content(), remaining);
            value.append(content);
            truncated |= attachment.truncated() || content.length() < attachment.content().trim().length();
        }
        return new BoundedText(value.toString(), truncated);
    }

    private BudgetedTurns conversationItems(List<PaperConversationTurn> values) {
        List<PaperContextSnapshot.ConversationItem> newestFirst = new ArrayList<>();
        int characters = 0;
        boolean truncated = false;
        for (int index = values.size() - 1; index >= 0; index--) {
            PaperConversationTurn turn = values.get(index);
            String question = bounded(turn.question(), 500);
            String answer = bounded(turn.answer(), 900);
            int next = question.length() + answer.length();
            if (!newestFirst.isEmpty() && characters + next > MAX_CONVERSATION_CHARACTERS) {
                truncated = true;
                continue;
            }
            if (characters + next > MAX_CONVERSATION_CHARACTERS) {
                int remaining = Math.max(0, MAX_CONVERSATION_CHARACTERS - question.length());
                answer = bounded(answer, remaining);
                next = question.length() + answer.length();
                truncated = true;
            }
            truncated |= question.length() < turn.question().trim().length()
                    || answer.length() < turn.answer().trim().length();
            LinkedHashSet<String> evidenceBlockIds = new LinkedHashSet<>(turn.selectionBlockIds());
            turn.evidenceRefs().forEach(ref -> evidenceBlockIds.add(ref.blockId()));
            newestFirst.add(new PaperContextSnapshot.ConversationItem(
                    turn.id(), question, answer, evidenceBlockIds.stream().limit(12).toList()));
            characters += next;
        }
        java.util.Collections.reverse(newestFirst);
        return new BudgetedTurns(List.copyOf(newestFirst), characters, truncated);
    }

    private BudgetedObservations observationItems(List<PaperMemoryObservation> values) {
        List<PaperContextSnapshot.ObservationItem> result = new ArrayList<>();
        int characters = 0;
        boolean truncated = false;
        for (PaperMemoryObservation observation : values) {
            String claim = bounded(observation.claimText(), 700);
            if (!result.isEmpty() && characters + claim.length() > MAX_OBSERVATION_CHARACTERS) {
                truncated = true;
                continue;
            }
            LinkedHashSet<String> blockIds = new LinkedHashSet<>();
            observation.evidenceRefs().forEach(ref -> blockIds.add(ref.blockId()));
            result.add(new PaperContextSnapshot.ObservationItem(
                    observation.id(), claim, blockIds.stream().limit(8).toList(),
                    observation.confirmationCount()));
            characters += claim.length();
            truncated |= claim.length() < observation.claimText().trim().length();
        }
        return new BudgetedObservations(List.copyOf(result), characters, truncated);
    }

    private BoundedText profileContext(long paperId, WorkbenchPlan.ArtifactVersion version) {
        PaperMemoryRecord record = memoryMapper.selectVersion(
                paperId, version.documentHash(), version.parserVersion(), PaperStructure.SCHEMA_VERSION);
        if (record == null || record.getProfileJson() == null || record.getProfileJson().isBlank()) {
            return new BoundedText("", false);
        }
        try {
            PaperGlobalProfile profile = objectMapper.readValue(
                    record.getProfileJson(), PaperGlobalProfile.class);
            StringBuilder value = new StringBuilder();
            append(value, "研究问题", profile.researchProblem());
            append(value, "方法", profile.methodSummary());
            appendClaims(value, "贡献", profile.coreContributions(), 3);
            appendClaims(value, "主要发现", profile.keyFindings(), 3);
            appendClaims(value, "局限", profile.limitations(), 3);
            if (!profile.openQuestions().isEmpty()) {
                append(value, "开放问题", String.join("；", profile.openQuestions().stream().limit(3).toList()));
            }
            String full = value.toString().trim();
            String bounded = bounded(full, MAX_PROFILE_CHARACTERS);
            return new BoundedText(bounded, bounded.length() < full.length());
        } catch (Exception ignored) {
            return new BoundedText("", false);
        }
    }

    private void appendClaims(StringBuilder value,
                              String label,
                              List<PaperMemoryClaim> claims,
                              int limit) {
        if (claims == null || claims.isEmpty()) return;
        List<String> rendered = claims.stream().limit(limit).map(claim -> {
            String citations = claim.evidenceBlockIds().isEmpty()
                    ? "" : " [block: " + String.join(", ", claim.evidenceBlockIds()) + "]";
            return claim.statement() + citations;
        }).toList();
        append(value, label, String.join("；", rendered));
    }

    private void append(StringBuilder value, String label, String content) {
        if (content == null || content.isBlank()) return;
        if (value.length() > 0) value.append('\n');
        value.append(label).append("：").append(content.trim());
    }

    private String bounded(String value, int max) {
        String normalized = value == null ? "" : value.trim();
        int safeMax = Math.max(0, max);
        return normalized.length() <= safeMax ? normalized : normalized.substring(0, safeMax);
    }

    private record BudgetedTurns(List<PaperContextSnapshot.ConversationItem> items,
                                 int characters,
                                 boolean truncated) { }

    private record BudgetedObservations(List<PaperContextSnapshot.ObservationItem> items,
                                        int characters,
                                        boolean truncated) { }

    private record BoundedText(String value, boolean truncated) { }
}
