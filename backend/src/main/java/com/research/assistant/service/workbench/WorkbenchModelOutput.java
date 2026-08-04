package com.research.assistant.service.workbench;

import com.research.assistant.service.pdf.layout.DocumentBlockContentMode;
import com.research.assistant.service.pdf.layout.LayoutEvidence;

import java.text.Normalizer;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Normalized product output; raw provider responses are never persisted in the trace. */
public record WorkbenchModelOutput(String answer,
                                   List<WorkbenchEvidenceGate.GroundedClaim> claims,
                                   AnnotationSuggestion annotationSuggestion,
                                   List<WorkbenchAnswerBlock> answerBlocks,
                                   List<WorkbenchAnswerRequirement> requirements) {
    public WorkbenchModelOutput {
        claims = claims == null ? List.of() : List.copyOf(claims);
        answerBlocks = answerBlocks == null ? List.of() : answerBlocks.stream()
                .filter(block -> block != null && !block.text().isBlank()).toList();
        requirements = requirements == null ? List.of() : requirements.stream()
                .filter(item -> item != null && !item.id().isBlank() && !item.content().isBlank())
                .toList();
        // Direct answer-block bindings are authoritative; legacy claims are derived from them
        // so providers cannot create two conflicting citation graphs in one response.
        if (!answerBlocks.isEmpty()) claims = claimsFrom(answerBlocks);
        answer = answer == null ? "" : answer.trim();
        if (answer.isBlank() && !answerBlocks.isEmpty()) {
            answer = answerBlocks.stream().map(WorkbenchAnswerBlock::text)
                    .collect(java.util.stream.Collectors.joining("\n\n"));
        }
    }

    public WorkbenchModelOutput(String answer,
                                List<WorkbenchEvidenceGate.GroundedClaim> claims,
                                AnnotationSuggestion annotationSuggestion) {
        this(answer, claims, annotationSuggestion, List.of(), List.of());
    }

    public WorkbenchModelOutput(String answer,
                                List<WorkbenchEvidenceGate.GroundedClaim> claims,
                                AnnotationSuggestion annotationSuggestion,
                                List<WorkbenchAnswerBlock> answerBlocks) {
        this(answer, claims, annotationSuggestion, answerBlocks, List.of());
    }

    public WorkbenchModelOutput normalizedFor(WorkbenchPlan.Workflow workflow) {
        if (workflow != WorkbenchPlan.Workflow.ANNOTATION_SUGGESTION || annotationSuggestion != null) {
            return this;
        }
        List<String> evidenceIds = claims.stream()
                .flatMap(claim -> claim.evidenceIds().stream())
                .collect(java.util.stream.Collectors.collectingAndThen(
                        java.util.stream.Collectors.toCollection(LinkedHashSet::new), List::copyOf));
        return new WorkbenchModelOutput(answer, claims,
                new AnnotationSuggestion(AnnotationType.COMMENT, answer, evidenceIds), answerBlocks,
                requirements);
    }

    public WorkbenchEvidenceGate.AnswerDraft toGateDraft(WorkbenchPlan.Workflow workflow) {
        WorkbenchModelOutput normalized = normalizedFor(workflow);
        if (workflow != WorkbenchPlan.Workflow.ANNOTATION_SUGGESTION
                || normalized.annotationSuggestion() == null) {
            return new WorkbenchEvidenceGate.AnswerDraft(normalized.answer(), normalized.claims(),
                    normalized.hasOnlyNonPaperBlocks(), normalized.answerBlocks(),
                    normalized.requirements());
        }
        List<WorkbenchEvidenceGate.GroundedClaim> gateClaims = new java.util.ArrayList<>(normalized.claims());
        gateClaims.add(new WorkbenchEvidenceGate.GroundedClaim(
                normalized.annotationSuggestion().content(), normalized.annotationSuggestion().evidenceIds()));
        return new WorkbenchEvidenceGate.AnswerDraft(normalized.answer(), gateClaims,
                normalized.hasOnlyNonPaperBlocks(), normalized.answerBlocks(),
                normalized.requirements());
    }

    public boolean hasOnlyNonPaperBlocks() {
        return !answerBlocks.isEmpty() && answerBlocks.stream()
                .noneMatch(WorkbenchAnswerBlock::requiresPaperEvidence);
    }

    /**
     * Canonicalizes safe evidence bindings without changing a paper-backed block's evidence IDs.
     * Providers commonly paraphrase quotes and sometimes attach paper citations to blocks they
     * explicitly label as general knowledge. The server owns exact excerpts and removes those
     * semantically invalid non-paper citations before deterministic validation.
     */
    public WorkbenchModelOutput normalizeEvidenceQuotes(Collection<LayoutEvidence> evidenceSet) {
        if (answerBlocks.isEmpty()) return this;
        Map<String, LayoutEvidence> evidenceById = new LinkedHashMap<>();
        if (evidenceSet != null) {
            evidenceSet.stream().filter(java.util.Objects::nonNull)
                    .forEach(item -> evidenceById.put(item.evidenceId(), item));
        }
        List<WorkbenchAnswerBlock> normalizedBlocks = answerBlocks.stream().map(block ->
                new WorkbenchAnswerBlock(block.text(), block.basis(), block.requiresPaperEvidence()
                        ? block.citations().stream()
                        .map(citation -> canonicalCitation(block.text(), citation, evidenceById)).toList()
                        : List.of(), block.requirementIds())).toList();
        return new WorkbenchModelOutput(answer, claims, annotationSuggestion, normalizedBlocks,
                requirements);
    }

    public WorkbenchModelOutput withRequirements(List<WorkbenchAnswerRequirement> replacement) {
        return new WorkbenchModelOutput(answer, claims, annotationSuggestion, answerBlocks,
                replacement);
    }

    private WorkbenchAnswerBlock.Citation canonicalCitation(
            String blockText,
            WorkbenchAnswerBlock.Citation citation,
            Map<String, LayoutEvidence> evidenceById) {
        LayoutEvidence evidence = evidenceById.get(citation.evidenceId());
        WorkbenchAnswerBlock.Citation boundCitation = citation;
        if (evidence == null) {
            List<LayoutEvidence> exactMatches = evidenceById.values().stream()
                    .filter(candidate -> !citation.quote().isBlank())
                    .filter(candidate -> normalize(searchableSource(candidate))
                            .contains(normalize(citation.quote())))
                    .toList();
            if (exactMatches.size() != 1) return citation;
            evidence = exactMatches.get(0);
            boundCitation = new WorkbenchAnswerBlock.Citation(
                    evidence.evidenceId(), citation.quote());
        }
        if (evidence.contentMode() == DocumentBlockContentMode.REGION) {
            return boundCitation;
        }
        String source = sourceText(evidence);
        if (source.isBlank()) return boundCitation;
        if (!boundCitation.quote().isBlank()
                && normalize(source).contains(normalize(boundCitation.quote()))) {
            return boundCitation;
        }
        String canonical = bestQuote(source, boundCitation.quote() + " " + blockText);
        return canonical == null ? boundCitation
                : new WorkbenchAnswerBlock.Citation(boundCitation.evidenceId(), canonical);
    }

    private String searchableSource(LayoutEvidence evidence) {
        return sourceText(evidence) + " " + String.join(" ", evidence.sectionPath());
    }

    private String sourceText(LayoutEvidence evidence) {
        String structured = evidence.structuredContent() == null ? "" : evidence.structuredContent().trim();
        String text = evidence.text() == null ? "" : evidence.text().trim();
        return structured.isBlank() ? text : structured;
    }

    private String bestQuote(String source, String claim) {
        List<String> sentences = java.util.Arrays.stream(source.split("(?<=[.!?。！？;；])\\s*|\\R+"))
                .map(String::trim).filter(value -> !value.isBlank()).toList();
        if (sentences.isEmpty()) sentences = List.of(source.trim());
        List<String> windows = new java.util.ArrayList<>();
        for (int start = 0; start < sentences.size(); start++) {
            StringBuilder window = new StringBuilder();
            for (int end = start; end < Math.min(sentences.size(), start + 6); end++) {
                if (!window.isEmpty()) window.append(' ');
                window.append(sentences.get(end));
                if (window.length() > 420) break;
                windows.add(window.toString());
            }
        }
        String best = windows.stream().max(java.util.Comparator
                        .comparingDouble((String value) -> overlap(value, claim))
                        .thenComparingInt(value -> -value.length()))
                .orElse(source.trim());
        if (overlap(best, claim) <= 0) return null;
        return best.length() <= 420 ? best : best.substring(0, 420).trim();
    }

    private double overlap(String source, String claim) {
        Set<String> sourceTerms = terms(source);
        Set<String> claimTerms = terms(claim);
        if (sourceTerms.isEmpty() || claimTerms.isEmpty()) return 0;
        long matched = claimTerms.stream().filter(sourceTerms::contains).count();
        return matched / (double) claimTerms.size();
    }

    private Set<String> terms(String value) {
        Set<String> result = new LinkedHashSet<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("[\\p{IsLatin}\\p{IsGreek}\\p{N}_+/-]{2,}|[\\p{IsHan}]{2,}")
                .matcher(normalize(value));
        while (matcher.find()) result.add(matcher.group());
        return result;
    }

    private String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private static List<WorkbenchEvidenceGate.GroundedClaim> claimsFrom(
            List<WorkbenchAnswerBlock> blocks) {
        return blocks.stream().filter(WorkbenchAnswerBlock::requiresPaperEvidence)
                .map(block -> new WorkbenchEvidenceGate.GroundedClaim(
                        block.text(), block.citations().stream()
                        .map(WorkbenchAnswerBlock.Citation::evidenceId).distinct().toList()))
                .toList();
    }

    public enum AnnotationType {
        COMMENT,
        SUMMARY,
        QUESTION,
        CRITIQUE
    }

    public record AnnotationSuggestion(AnnotationType type, String content, List<String> evidenceIds) {
        public AnnotationSuggestion {
            type = type == null ? AnnotationType.COMMENT : type;
            content = content == null ? "" : content.trim();
            evidenceIds = evidenceIds == null ? List.of() : evidenceIds.stream()
                    .filter(id -> id != null && !id.isBlank()).distinct().toList();
        }
    }
}
