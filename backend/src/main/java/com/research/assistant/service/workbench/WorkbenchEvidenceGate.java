package com.research.assistant.service.workbench;

import com.research.assistant.service.pdf.layout.LayoutEvidence;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Deterministic grounding boundary. Unknown citations can never reach the final answer. */
@Component
public class WorkbenchEvidenceGate {

    private static final int MAX_ANSWER_CHARS = 60_000;
    private static final int MAX_CLAIMS = 100;
    private static final int MAX_CITATIONS_PER_CLAIM = 8;

    public GateResult validate(AnswerDraft draft,
                               Collection<LayoutEvidence> evidenceSet,
                               GatePolicy policy) {
        GatePolicy effectivePolicy = policy == null ? GatePolicy.strict(0) : policy;
        Set<String> candidates = new LinkedHashSet<>();
        Set<String> presentButUngrounded = new LinkedHashSet<>();
        java.util.Map<String, Long> paperByEvidenceId = new java.util.LinkedHashMap<>();
        java.util.Map<String, LayoutEvidence> evidenceById = new java.util.LinkedHashMap<>();
        Set<String> selectedEvidenceIds = new LinkedHashSet<>();
        if (evidenceSet != null) {
            evidenceSet.stream()
                    .filter(item -> item != null && item.evidenceId() != null && !item.evidenceId().isBlank())
                    .forEach(item -> {
                        if (item.selected() || item.score() > 0) {
                            candidates.add(item.evidenceId());
                            paperByEvidenceId.put(item.evidenceId(), item.paperId());
                            evidenceById.put(item.evidenceId(), item);
                            if (item.selected()) selectedEvidenceIds.add(item.evidenceId());
                        } else {
                            presentButUngrounded.add(item.evidenceId());
                        }
                    });
        }

        List<String> issues = new ArrayList<>();
        if (draft == null || draft.answer().isBlank()) issues.add("answer is blank");
        if (draft != null && draft.answer().length() > MAX_ANSWER_CHARS) issues.add("answer exceeds 60000 characters");

        List<GroundedClaim> claims = draft == null ? List.of() : draft.claims();
        if (claims.size() > MAX_CLAIMS) issues.add("answer contains more than 100 claims");
        if (effectivePolicy.evidenceRequired() && candidates.isEmpty()) issues.add("evidence set is empty");
        if (effectivePolicy.evidenceRequired() && claims.isEmpty()
                && (draft == null || !draft.onlyNonPaperBlocks())) {
            issues.add("grounded claims are required");
        }

        int evaluatedClaims = 0;
        int groundedClaims = 0;
        Set<String> validIds = new LinkedHashSet<>();
        Set<String> invalidIds = new LinkedHashSet<>();
        Set<String> irrelevantIds = new LinkedHashSet<>();

        for (int index = 0; index < Math.min(claims.size(), MAX_CLAIMS); index++) {
            GroundedClaim claim = claims.get(index);
            evaluatedClaims += 1;
            if (claim == null || claim.text().isBlank()) {
                issues.add("claim " + index + " is blank");
                continue;
            }
            if (claim.evidenceIds().size() > MAX_CITATIONS_PER_CLAIM) {
                issues.add("claim " + index + " has more than 8 citations");
            }
            boolean hasValidCitation = false;
            for (String evidenceId : claim.evidenceIds()) {
                if (candidates.contains(evidenceId)) {
                    validIds.add(evidenceId);
                    hasValidCitation = true;
                } else if (presentButUngrounded.contains(evidenceId)) {
                    irrelevantIds.add(evidenceId);
                } else if (evidenceId != null && !evidenceId.isBlank()) {
                    invalidIds.add(evidenceId);
                }
            }
            if (hasValidCitation) groundedClaims += 1;
        }
        validateAnswerBlocks(draft, evidenceById, issues);

        if (!invalidIds.isEmpty()) issues.add("answer cites evidence outside the current run");
        if (!irrelevantIds.isEmpty()) issues.add("answer cites evidence with no query relevance");
        Set<Long> citedPaperIds = validIds.stream().map(paperByEvidenceId::get)
                .filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.toSet());
        if (!citedPaperIds.containsAll(effectivePolicy.requiredPaperIds())) {
            issues.add("answer does not cite every required paper");
        }
        if (effectivePolicy.requireSelectedEvidence()
                && !claims.isEmpty()
                && validIds.stream().noneMatch(selectedEvidenceIds::contains)) {
            issues.add("answer does not cite the selected passage");
        }
        double coverage = evaluatedClaims == 0
                ? (draft != null && draft.onlyNonPaperBlocks() ? 1
                : effectivePolicy.evidenceRequired() ? 0 : 1)
                : groundedClaims / (double) evaluatedClaims;
        if (coverage + 1e-9 < effectivePolicy.minimumClaimCoverage()) {
            issues.add("claim evidence coverage is below the required threshold");
        }

        Decision decision = issues.isEmpty()
                ? Decision.PASS
                : effectivePolicy.repairAttempt() < effectivePolicy.repairLimit()
                ? Decision.REPAIR : Decision.REJECT;
        Set<String> rejectedIds = new LinkedHashSet<>(invalidIds);
        rejectedIds.addAll(irrelevantIds);
        return new GateResult(decision, coverage, List.copyOf(validIds),
                List.copyOf(rejectedIds), List.copyOf(issues));
    }

    public enum Decision {
        PASS,
        REPAIR,
        REJECT
    }

    public record GroundedClaim(String text, List<String> evidenceIds) {
        public GroundedClaim {
            text = text == null ? "" : text.trim();
            evidenceIds = evidenceIds == null ? List.of() : evidenceIds.stream()
                    .filter(id -> id != null && !id.isBlank())
                    .distinct()
                    .toList();
        }
    }

    private void validateAnswerBlocks(AnswerDraft draft,
                                      java.util.Map<String, LayoutEvidence> evidenceById,
                                      List<String> issues) {
        if (draft == null || draft.answerBlocks().isEmpty()) return;
        for (int index = 0; index < draft.answerBlocks().size(); index++) {
            WorkbenchAnswerBlock block = draft.answerBlocks().get(index);
            if (block.requiresPaperEvidence() && block.citations().isEmpty()) {
                issues.add("answer block " + index + " has no paper citation");
            }
            if (!block.requiresPaperEvidence() && !block.citations().isEmpty()) {
                issues.add("answer block " + index + " attaches paper citations to non-paper knowledge");
            }
            if (block.basis() == WorkbenchAnswerBlock.Basis.INFERENCE
                    && !containsInferenceCue(block.text())) {
                issues.add("answer block " + index + " does not label its inference");
            }
            for (WorkbenchAnswerBlock.Citation citation : block.citations()) {
                LayoutEvidence evidence = evidenceById.get(citation.evidenceId());
                if (evidence == null) continue;
                if (evidence.contentMode()
                        == com.research.assistant.service.pdf.layout.DocumentBlockContentMode.REGION) {
                    continue;
                }
                if (citation.quote().isBlank()) {
                    issues.add("answer block " + index + " citation has no source quote");
                    continue;
                }
                String source = normalizeQuote(evidence.text() + " " + evidence.structuredContent());
                if (!source.contains(normalizeQuote(citation.quote()))) {
                    issues.add("answer block " + index + " citation quote is not in evidence");
                }
            }
        }
    }

    private boolean containsInferenceCue(String text) {
        String normalized = text == null ? "" : text.toLowerCase(java.util.Locale.ROOT);
        return List.of("推断", "可能", "表明", "暗示", "suggest", "infer", "indicate", "likely")
                .stream().anyMatch(normalized::contains);
    }

    private String normalizeQuote(String value) {
        return java.text.Normalizer.normalize(value == null ? "" : value,
                        java.text.Normalizer.Form.NFKC)
                .toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    public record AnswerDraft(String answer,
                              List<GroundedClaim> claims,
                              boolean onlyNonPaperBlocks,
                              List<WorkbenchAnswerBlock> answerBlocks) {
        public AnswerDraft {
            answer = answer == null ? "" : answer.trim();
            claims = claims == null ? List.of() : List.copyOf(claims);
            answerBlocks = answerBlocks == null ? List.of() : List.copyOf(answerBlocks);
        }

        public AnswerDraft(String answer, List<GroundedClaim> claims) {
            this(answer, claims, false, List.of());
        }

        public AnswerDraft(String answer, List<GroundedClaim> claims, boolean onlyNonPaperBlocks) {
            this(answer, claims, onlyNonPaperBlocks, List.of());
        }
    }

    public record GatePolicy(boolean evidenceRequired,
                             double minimumClaimCoverage,
                             int repairAttempt,
                             int repairLimit,
                             Set<Long> requiredPaperIds,
                             boolean requireSelectedEvidence) {
        public GatePolicy {
            requiredPaperIds = requiredPaperIds == null ? Set.of() : Set.copyOf(requiredPaperIds);
            if (minimumClaimCoverage < 0 || minimumClaimCoverage > 1) {
                throw new IllegalArgumentException("minimum claim coverage must be between 0 and 1");
            }
            if (repairAttempt < 0 || repairAttempt > 1 || repairLimit < 0 || repairLimit > 1
                    || repairAttempt > repairLimit) {
                throw new IllegalArgumentException("evidence repair is limited to one attempt");
            }
        }

        public static GatePolicy strict(int repairAttempt) {
            return new GatePolicy(true, 1.0, repairAttempt, 1, Set.of(), false);
        }

        public static GatePolicy selection(int repairAttempt) {
            return new GatePolicy(true, 1.0, repairAttempt, 1, Set.of(), true);
        }

        public static GatePolicy comparison(int repairAttempt, Set<Long> paperIds) {
            return new GatePolicy(true, 1.0, repairAttempt, 1, paperIds, false);
        }
    }

    public record GateResult(Decision decision,
                             double claimCoverage,
                             List<String> validEvidenceIds,
                             List<String> invalidEvidenceIds,
                             List<String> issues) {
        public GateResult {
            validEvidenceIds = validEvidenceIds == null ? List.of() : List.copyOf(validEvidenceIds);
            invalidEvidenceIds = invalidEvidenceIds == null ? List.of() : List.copyOf(invalidEvidenceIds);
            issues = issues == null ? List.of() : List.copyOf(issues);
        }
    }
}
