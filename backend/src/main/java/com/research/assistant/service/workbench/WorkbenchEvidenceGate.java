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
        Set<String> selectedEvidenceIds = new LinkedHashSet<>();
        if (evidenceSet != null) {
            evidenceSet.stream()
                    .filter(item -> item != null && item.evidenceId() != null && !item.evidenceId().isBlank())
                    .forEach(item -> {
                        if (item.selected() || item.score() > 0) {
                            candidates.add(item.evidenceId());
                            paperByEvidenceId.put(item.evidenceId(), item.paperId());
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

    public record AnswerDraft(String answer, List<GroundedClaim> claims, boolean onlyNonPaperBlocks) {
        public AnswerDraft {
            answer = answer == null ? "" : answer.trim();
            claims = claims == null ? List.of() : List.copyOf(claims);
        }

        public AnswerDraft(String answer, List<GroundedClaim> claims) {
            this(answer, claims, false);
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
