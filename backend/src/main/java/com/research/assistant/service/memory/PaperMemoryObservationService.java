package com.research.assistant.service.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.entity.PaperConversationTurnRecord;
import com.research.assistant.entity.PaperMemoryObservationRecord;
import com.research.assistant.mapper.PaperConversationTurnMapper;
import com.research.assistant.mapper.PaperMemoryObservationMapper;
import com.research.assistant.service.pdf.layout.LayoutEvidence;
import com.research.assistant.service.pdf.layout.LayoutTextSimilarity;
import com.research.assistant.service.workbench.WorkbenchEvidenceGate;
import com.research.assistant.service.workbench.WorkbenchPlan;
import com.research.assistant.service.workbench.WorkbenchRunTrace;
import com.research.assistant.service.workbench.WorkbenchWorkflowResult;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Persists validated turns and promotes only their grounded claims into per-paper memory. */
@Service
public class PaperMemoryObservationService {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };
    private static final TypeReference<List<WorkbenchEvidenceGate.GroundedClaim>> CLAIM_LIST =
            new TypeReference<>() { };
    private static final TypeReference<List<PaperMemoryEvidenceRef>> EVIDENCE_LIST =
            new TypeReference<>() { };

    private final PaperConversationTurnMapper turnMapper;
    private final PaperMemoryObservationMapper observationMapper;
    private final ObjectMapper objectMapper;

    public PaperMemoryObservationService(PaperConversationTurnMapper turnMapper,
                                         PaperMemoryObservationMapper observationMapper,
                                         ObjectMapper objectMapper) {
        this.turnMapper = turnMapper;
        this.observationMapper = observationMapper;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void remember(WorkbenchRunTrace trace, WorkbenchWorkflowResult result) {
        if (trace == null || result == null
                || trace.plan().workflow() != WorkbenchPlan.Workflow.SELECTION_QA
                || trace.invocation().paperIds().size() != 1) return;
        long paperId = trace.invocation().paperIds().get(0);
        WorkbenchPlan.ArtifactVersion version = trace.artifactVersions().stream()
                .filter(item -> item.paperId().equals(paperId))
                .findFirst().orElse(null);
        if (version == null) return;

        Map<String, LayoutEvidence> validEvidence = new LinkedHashMap<>();
        for (LayoutEvidence item : result.evidence()) {
            if (item != null && item.paperId() != null && paperId == item.paperId()
                    && version.documentHash().equals(item.documentHash())
                    && version.parserVersion().equals(item.parserVersion())) {
                validEvidence.put(item.evidenceId(), item);
            }
        }
        List<WorkbenchEvidenceGate.GroundedClaim> groundedClaims = result.claims().stream()
                .map(claim -> groundedClaim(claim, validEvidence))
                .filter(claim -> !claim.evidenceIds().isEmpty())
                .toList();
        if (groundedClaims.isEmpty()) return;

        List<PaperMemoryEvidenceRef> allRefs = evidenceRefs(groundedClaims, validEvidence);
        String conversationId = trace.invocation().conversationId();
        if (!conversationId.isBlank()) {
            saveTurn(trace, result, version, groundedClaims, allRefs, conversationId);
        }
        for (WorkbenchEvidenceGate.GroundedClaim claim : groundedClaims) {
            saveObservation(paperId, version, trace.runId(), conversationId,
                    claim, evidenceRefs(List.of(claim), validEvidence));
        }
    }

    public List<PaperConversationTurn> recentConversation(long paperId,
                                                           String conversationId,
                                                           String documentHash,
                                                           String parserVersion,
                                                           int limit) {
        if (conversationId == null || conversationId.isBlank()) return List.of();
        int bounded = Math.max(1, Math.min(limit, 12));
        return turnMapper.selectRecentConversation(
                        paperId, conversationId, documentHash, parserVersion, bounded).stream()
                .map(this::toTurn)
                .toList();
    }

    public List<PaperMemoryObservation> relevantObservations(long paperId,
                                                              String documentHash,
                                                              String parserVersion,
                                                              String query,
                                                              String excludedConversationId,
                                                              int limit) {
        int bounded = Math.max(1, Math.min(limit, 12));
        List<PaperMemoryObservationRecord> candidates = observationMapper.selectRecentVersion(
                paperId, documentHash, parserVersion, 80);
        return candidates.stream()
                .filter(item -> excludedConversationId == null || excludedConversationId.isBlank()
                        || !excludedConversationId.equals(item.getSourceConversationId()))
                .sorted(Comparator.comparingDouble(
                        (PaperMemoryObservationRecord item) -> observationScore(item, query)).reversed()
                        .thenComparing(PaperMemoryObservationRecord::getLastConfirmedAt,
                                Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(bounded)
                .map(this::toObservation)
                .toList();
    }

    private void saveTurn(WorkbenchRunTrace trace,
                          WorkbenchWorkflowResult result,
                          WorkbenchPlan.ArtifactVersion version,
                          List<WorkbenchEvidenceGate.GroundedClaim> claims,
                          List<PaperMemoryEvidenceRef> evidenceRefs,
                          String conversationId) {
        if (turnMapper.selectBySourceRunId(trace.runId()) != null) return;
        PaperConversationTurnRecord record = new PaperConversationTurnRecord();
        record.setPaperId(trace.invocation().paperIds().get(0));
        record.setConversationId(conversationId);
        record.setSourceRunId(trace.runId());
        record.setDocumentHash(version.documentHash());
        record.setParserVersion(version.parserVersion());
        record.setQuestion(bounded(trace.invocation().question(), 4_000));
        record.setAnswer(bounded(result.answer(), 60_000));
        record.setSelectionBlockIdsJson(write(result.evidence().stream()
                .filter(LayoutEvidence::selected)
                .map(LayoutEvidence::blockId)
                .filter(id -> id != null && !id.isBlank())
                .distinct().toList()));
        record.setClaimsJson(write(claims));
        record.setEvidenceRefsJson(write(evidenceRefs));
        record.setCreatedAt(LocalDateTime.now());
        try {
            turnMapper.insert(record);
        } catch (DuplicateKeyException ignored) {
            // A retried completion already stored the same source run.
        }
    }

    private void saveObservation(long paperId,
                                 WorkbenchPlan.ArtifactVersion version,
                                 String runId,
                                 String conversationId,
                                 WorkbenchEvidenceGate.GroundedClaim claim,
                                 List<PaperMemoryEvidenceRef> refs) {
        String fingerprint = fingerprint(claim.text());
        PaperMemoryObservationRecord existing = observationMapper.selectVersionClaim(
                paperId, version.documentHash(), version.parserVersion(), fingerprint);
        if (existing == null) {
            LocalDateTime now = LocalDateTime.now();
            PaperMemoryObservationRecord record = new PaperMemoryObservationRecord();
            record.setPaperId(paperId);
            record.setDocumentHash(version.documentHash());
            record.setParserVersion(version.parserVersion());
            record.setClaimFingerprint(fingerprint);
            record.setClaimText(bounded(claim.text(), 8_000));
            record.setEvidenceRefsJson(write(refs));
            record.setSourceRunId(runId);
            record.setSourceConversationId(blankToNull(conversationId));
            record.setConfirmationCount(1);
            record.setStatus("ACTIVE");
            record.setFirstSeenAt(now);
            record.setLastConfirmedAt(now);
            record.setCreatedAt(now);
            record.setUpdatedAt(now);
            try {
                observationMapper.insert(record);
                return;
            } catch (DuplicateKeyException ignored) {
                existing = observationMapper.selectVersionClaim(
                        paperId, version.documentHash(), version.parserVersion(), fingerprint);
            }
        }
        if (existing == null || runId.equals(existing.getSourceRunId())) return;
        existing.setEvidenceRefsJson(write(mergeRefs(readEvidence(existing.getEvidenceRefsJson()), refs)));
        existing.setSourceRunId(runId);
        existing.setSourceConversationId(blankToNull(conversationId));
        existing.setConfirmationCount(Math.max(1, value(existing.getConfirmationCount())) + 1);
        existing.setLastConfirmedAt(LocalDateTime.now());
        existing.setUpdatedAt(LocalDateTime.now());
        observationMapper.updateById(existing);
    }

    private WorkbenchEvidenceGate.GroundedClaim groundedClaim(
            WorkbenchEvidenceGate.GroundedClaim claim,
            Map<String, LayoutEvidence> evidence) {
        if (claim == null) return new WorkbenchEvidenceGate.GroundedClaim("", List.of());
        return new WorkbenchEvidenceGate.GroundedClaim(claim.text(), claim.evidenceIds().stream()
                .filter(evidence::containsKey).distinct().toList());
    }

    private List<PaperMemoryEvidenceRef> evidenceRefs(
            List<WorkbenchEvidenceGate.GroundedClaim> claims,
            Map<String, LayoutEvidence> evidence) {
        LinkedHashSet<String> citedIds = new LinkedHashSet<>();
        claims.forEach(claim -> citedIds.addAll(claim.evidenceIds()));
        return citedIds.stream().map(evidence::get).filter(java.util.Objects::nonNull)
                .map(item -> new PaperMemoryEvidenceRef(
                        item.evidenceId(), item.blockId(), item.page(), item.sectionPath(),
                        item.documentHash(), item.parserVersion()))
                .toList();
    }

    private List<PaperMemoryEvidenceRef> mergeRefs(List<PaperMemoryEvidenceRef> first,
                                                   List<PaperMemoryEvidenceRef> second) {
        Map<String, PaperMemoryEvidenceRef> merged = new LinkedHashMap<>();
        first.forEach(item -> merged.put(item.evidenceId(), item));
        second.forEach(item -> merged.put(item.evidenceId(), item));
        return merged.values().stream().limit(16).toList();
    }

    private PaperConversationTurn toTurn(PaperConversationTurnRecord record) {
        return new PaperConversationTurn(
                record.getId() == null ? 0 : record.getId(), record.getSourceRunId(),
                record.getQuestion(), record.getAnswer(),
                read(record.getSelectionBlockIdsJson(), STRING_LIST),
                read(record.getClaimsJson(), CLAIM_LIST),
                readEvidence(record.getEvidenceRefsJson()),
                toInstant(record.getCreatedAt()));
    }

    private PaperMemoryObservation toObservation(PaperMemoryObservationRecord record) {
        return new PaperMemoryObservation(
                record.getId() == null ? 0 : record.getId(), record.getClaimText(),
                readEvidence(record.getEvidenceRefsJson()), record.getSourceRunId(),
                record.getSourceConversationId(), value(record.getConfirmationCount()),
                toInstant(record.getLastConfirmedAt()));
    }

    private double observationScore(PaperMemoryObservationRecord record, String query) {
        double lexical = LayoutTextSimilarity.queryCoverage(query, record.getClaimText());
        double confirmations = Math.min(0.20, Math.log1p(value(record.getConfirmationCount())) * 0.05);
        return lexical + confirmations;
    }

    private String fingerprint(String claim) {
        String normalized = claim == null ? "" : claim.strip().toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("论文问答记忆无法序列化", exception);
        }
    }

    private <T> T read(String json, TypeReference<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception exception) {
            throw new IllegalStateException("论文问答记忆无法读取", exception);
        }
    }

    private List<PaperMemoryEvidenceRef> readEvidence(String json) {
        return read(json, EVIDENCE_LIST);
    }

    private String bounded(String value, int max) {
        String normalized = value == null ? "" : value.trim();
        return normalized.length() <= max ? normalized : normalized.substring(0, max);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private int value(Integer value) { return value == null ? 0 : Math.max(0, value); }

    private java.time.Instant toInstant(LocalDateTime value) {
        return (value == null ? LocalDateTime.now() : value)
                .atZone(ZoneId.systemDefault()).toInstant();
    }
}
