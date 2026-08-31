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
        if (answer == null) throw new IllegalArgumentException("answer is required");
        if (catalog == null) throw new IllegalArgumentException("source catalog is required");
        List<CitationRequest> ordered = requests == null ? List.of() : requests.stream()
                .sorted(Comparator.comparingInt(CitationRequest::answerStart)
                        .thenComparingInt(CitationRequest::answerEnd))
                .toList();

        Map<String, Integer> numberBySource = new LinkedHashMap<>();
        List<CitationBinding> bindings = new ArrayList<>();
        Map<Integer, CitationBinding> evidenceByNumber = new LinkedHashMap<>();
        for (CitationRequest request : ordered) {
            if (request.answerEnd() > answer.length()) {
                throw new IllegalArgumentException("citation answer span is outside final answer");
            }
            SourceObject source = catalog.requireObject(request.sourceObjectId());
            validateVersion(source, catalog);
            String quote = evidencePreview(source);

            List<SourceLocator> available = catalog.requireLocators(request.sourceObjectId());
            Set<String> availableIds = available.stream().map(SourceLocator::locatorId)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            List<String> locatorIds = request.locatorIds().isEmpty()
                    ? List.copyOf(availableIds) : request.locatorIds();
            if (!availableIds.containsAll(locatorIds)) {
                throw new IllegalArgumentException("citation locator does not belong to source object");
            }

            int number = numberBySource.computeIfAbsent(request.sourceObjectId(), ignored -> numberBySource.size() + 1);
            CitationBinding binding = new CitationBinding("cite-" + number, number,
                    request.answerStart(), request.answerEnd(), request.sourceObjectId(),
                    quote, locatorIds);
            bindings.add(binding);
            evidenceByNumber.putIfAbsent(number, binding);
        }
        return new GroundedAnswer(answer, bindings, List.copyOf(evidenceByNumber.values()));
    }

    private static void validateVersion(SourceObject source, PaperSourceCatalog catalog) {
        if (source.paperId() != catalog.paperId()
                || !source.documentHash().equals(catalog.documentHash())
                || !source.parserVersion().equals(catalog.parserVersion())) {
            throw new IllegalArgumentException("citation source belongs to a stale paper version");
        }
    }

    private static String evidencePreview(SourceObject source) {
        String value = source.rawContent().strip();
        if (value.length() <= 320) return value;
        return value.substring(0, 319) + "…";
    }
}
