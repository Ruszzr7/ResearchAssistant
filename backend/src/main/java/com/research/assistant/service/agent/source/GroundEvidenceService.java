package com.research.assistant.service.agent.source;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class GroundEvidenceService {

    public GroundedAnswer ground(String answer,
                                 List<CitationRequest> requests,
                                 PaperSourceCatalog catalog) {
        if (answer == null) throw new IllegalArgumentException("答案不能为空");
        if (catalog == null) throw new IllegalArgumentException("论文来源目录不能为空");
        List<CitationRequest> ordered = requests == null ? List.of() : requests.stream()
                .sorted(Comparator.comparingInt(CitationRequest::answerStart)
                        .thenComparingInt(CitationRequest::answerEnd))
                .toList();

        List<CanonicalEvidence> canonical = new ArrayList<>();
        List<BindingDraft> drafts = new ArrayList<>();
        for (CitationRequest request : ordered) {
            if (request.answerEnd() > answer.length()) {
                throw new IllegalArgumentException("引用答案范围超出最终答案");
            }
            SourceObject source = catalog.requireObject(request.sourceObjectId());
            validateVersion(source, catalog);
            List<SourceLocator> available = catalog.requireLocators(request.sourceObjectId());
            Set<String> availableIds = available.stream().map(SourceLocator::locatorId)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            List<String> locatorIds = request.locatorIds().isEmpty()
                    ? List.copyOf(availableIds) : request.locatorIds();
            if (!availableIds.containsAll(locatorIds)) {
                throw new IllegalArgumentException("引用定位不属于指定来源");
            }

            List<SourceLocator> selectedLocators = available.stream()
                    .filter(locator -> locatorIds.contains(locator.locatorId()))
                    .toList();
            CanonicalEvidence match = canonical.stream()
                    .filter(candidate -> candidate.source.sourceObjectId().equals(source.sourceObjectId())
                            || SourceEvidenceIdentity.key(candidate.source, candidate.locators)
                            .equals(SourceEvidenceIdentity.key(source, selectedLocators))
                            || SourceEvidenceIdentity.equivalent(candidate.source,
                            candidate.locators, source, selectedLocators))
                    .findFirst().orElse(null);
            if (match == null) {
                match = new CanonicalEvidence(canonical.size() + 1, source, selectedLocators,
                        List.copyOf(locatorIds));
                canonical.add(match);
            } else if (match.source.sourceObjectId().equals(source.sourceObjectId())) {
                match.locators = mergeLocators(match.locators, selectedLocators);
                match.locatorIds = match.locators.stream().map(SourceLocator::locatorId).toList();
            } else if (source.rawContent().length() > match.source.rawContent().length()) {
                match.source = source;
                match.locators = selectedLocators;
                match.locatorIds = List.copyOf(locatorIds);
            }
            drafts.add(new BindingDraft(request.answerStart(), request.answerEnd(), match.number));
        }
        Map<Integer, CanonicalEvidence> byNumber = canonical.stream()
                .collect(java.util.stream.Collectors.toMap(item -> item.number, item -> item,
                        (first, ignored) -> first, LinkedHashMap::new));
        List<CitationBinding> bindings = drafts.stream().distinct().map(draft -> {
            CanonicalEvidence item = byNumber.get(draft.number);
            return new CitationBinding("cite-" + draft.number, draft.number,
                    draft.answerStart, draft.answerEnd, item.source.sourceObjectId(),
                    evidencePreview(item.source), item.locatorIds);
        }).toList();
        List<CitationBinding> evidenceEntries = canonical.stream().map(item -> new CitationBinding(
                "cite-" + item.number, item.number, 0, 0, item.source.sourceObjectId(),
                evidencePreview(item.source), item.locatorIds)).toList();
        return new GroundedAnswer(answer, bindings, evidenceEntries);
    }

    private static void validateVersion(SourceObject source, PaperSourceCatalog catalog) {
        if (source.paperId() != catalog.paperId()
                || !source.documentHash().equals(catalog.documentHash())
                || !source.parserVersion().equals(catalog.parserVersion())) {
            throw new IllegalArgumentException("引用来源属于过期的论文版本");
        }
    }

    private static String evidencePreview(SourceObject source) {
        if (source.contentType() == SourceContentType.FORMULA
                && !Boolean.parseBoolean(source.provenance().getOrDefault("textReliable", "false"))) {
            String label = source.formulaNumber().isBlank() ? "公式区域" : "公式 (" + source.formulaNumber() + ")";
            return label + "的文本提取不可靠，请查看原始页面区域。";
        }
        String value = source.rawContent().strip();
        if (value.length() <= 320) return value;
        return value.substring(0, 319) + "…";
    }

    private static List<SourceLocator> mergeLocators(List<SourceLocator> first,
                                                     List<SourceLocator> second) {
        Map<String, SourceLocator> merged = new LinkedHashMap<>();
        for (SourceLocator locator : first) merged.put(locator.locatorId(), locator);
        for (SourceLocator locator : second) merged.putIfAbsent(locator.locatorId(), locator);
        return List.copyOf(merged.values());
    }

    private static final class CanonicalEvidence {
        private final int number;
        private SourceObject source;
        private List<SourceLocator> locators;
        private List<String> locatorIds;

        private CanonicalEvidence(int number, SourceObject source,
                                  List<SourceLocator> locators, List<String> locatorIds) {
            this.number = number;
            this.source = source;
            this.locators = locators;
            this.locatorIds = locatorIds;
        }
    }

    private record BindingDraft(int answerStart, int answerEnd, int number) { }
}
