package com.research.assistant.service.memory;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Applies deterministic quality gates after model generation. This does not ask
 * another model to judge a profile and therefore cannot introduce new facts.
 */
@Component
public class PaperProfileQualityValidator {

    public PaperProfileQualityReport validate(PaperStructure structure, PaperGlobalProfile profile) {
        LinkedHashSet<String> issues = new LinkedHashSet<>();
        if (structure == null || profile == null) {
            return new PaperProfileQualityReport(false, false,
                    List.of("PROFILE_MISSING"), Map.of());
        }

        if (!PaperGlobalProfile.SCHEMA_VERSION.equals(profile.schemaVersion())) {
            issues.add("PROFILE_SCHEMA_MISMATCH");
        }
        if (structure.paperId() == null || !structure.paperId().equals(profile.paperId())) {
            issues.add("PROFILE_PAPER_ID_MISMATCH");
        }
        if (!normalized(structure.metadata().title()).equals(normalized(profile.title()))) {
            issues.add("PROFILE_TITLE_MISMATCH");
        }
        if (profile.researchProblem().isBlank()) issues.add("RESEARCH_PROBLEM_MISSING");
        if (profile.methodSummary().isBlank()) issues.add("METHOD_SUMMARY_MISSING");
        if (profile.coreContributions().isEmpty()) issues.add("CORE_CONTRIBUTIONS_MISSING");
        if (profile.keyFindings().isEmpty()) issues.add("KEY_FINDINGS_MISSING");
        if (!profile.coverage().complete()) issues.add("PROFILE_COVERAGE_INCOMPLETE");
        if (profile.qualityIssues().contains("PROFILE_INPUT_TRUNCATED")) {
            issues.add("PROFILE_INPUT_TRUNCATED");
        }

        Set<String> validBlockIds = new LinkedHashSet<>(structure.readingOrder());
        structure.pages().forEach(page -> validBlockIds.addAll(page.blockIds()));
        int invalidEvidence = 0;
        int groundedClaims = 0;
        for (PaperMemoryClaim claim : allClaims(profile)) {
            if (claim.statement().isBlank() || claim.evidenceBlockIds().isEmpty()) {
                invalidEvidence++;
                continue;
            }
            boolean valid = claim.evidenceBlockIds().stream().allMatch(validBlockIds::contains);
            if (valid) groundedClaims++;
            else invalidEvidence++;
        }
        for (PaperGlobalProfile.BenchmarkResult benchmark : profile.benchmarkResults()) {
            if (benchmark.evidenceBlockIds().isEmpty()
                    || !benchmark.evidenceBlockIds().stream().allMatch(validBlockIds::contains)) {
                invalidEvidence++;
            }
        }
        if (invalidEvidence > 0) issues.add("INVALID_PROFILE_EVIDENCE");
        if (groundedClaims == 0) issues.add("NO_GROUNDED_PROFILE_CLAIMS");

        boolean identityValid = !issues.contains("PROFILE_SCHEMA_MISMATCH")
                && !issues.contains("PROFILE_PAPER_ID_MISMATCH")
                && !issues.contains("PROFILE_TITLE_MISMATCH");
        boolean usable = identityValid
                && (!profile.researchProblem().isBlank()
                || !profile.methodSummary().isBlank()
                || groundedClaims > 0);
        boolean ready = usable && issues.isEmpty();

        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("groundedClaims", groundedClaims);
        counts.put("invalidEvidence", invalidEvidence);
        counts.put("contributions", profile.coreContributions().size());
        counts.put("findings", profile.keyFindings().size());
        counts.put("limitations", profile.limitations().size());
        counts.put("summarizedChunks", profile.coverage().summarizedChunks());
        counts.put("totalChunks", profile.coverage().totalChunks());
        return new PaperProfileQualityReport(usable, ready, List.copyOf(issues), counts);
    }

    private static List<PaperMemoryClaim> allClaims(PaperGlobalProfile profile) {
        List<PaperMemoryClaim> claims = new ArrayList<>();
        claims.addAll(profile.coreContributions());
        claims.addAll(profile.keyFindings());
        claims.addAll(profile.limitations());
        return claims;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim().toLowerCase(java.util.Locale.ROOT);
    }
}
