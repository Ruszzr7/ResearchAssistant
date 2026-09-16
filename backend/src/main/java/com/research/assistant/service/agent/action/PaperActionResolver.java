package com.research.assistant.service.agent.action;

import com.research.assistant.service.agent.source.PaperSourceCatalog;
import com.research.assistant.service.agent.source.SourceLocator;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class PaperActionResolver {
    public ActionTarget resolve(PaperSourceCatalog catalog, String sourceObjectId) {
        if (catalog == null) throw new IllegalArgumentException("论文来源尚未就绪");
        catalog.requireObject(sourceObjectId);
        List<SourceLocator> locators = catalog.requireLocators(sourceObjectId);
        int page = locators.get(0).pageNumber();
        // A semantic source may span pages, while one UI action needs one physical
        // target. Resolve it deterministically to the source's first actionable
        // page and carry those locator IDs in the signed ticket.
        List<SourceLocator> actionLocators = locators.stream()
                .filter(locator -> locator.pageNumber() == page)
                .toList();
        String targetText = actionLocators.stream().map(SourceLocator::targetText)
                .filter(text -> text != null && !text.isBlank())
                .collect(java.util.stream.Collectors.joining("\n"));
        com.research.assistant.service.pdf.layout.EvidenceLocator.Precision precision = actionLocators.stream()
                .map(SourceLocator::precision)
                .findFirst()
                .orElse(com.research.assistant.service.pdf.layout.EvidenceLocator.Precision.BLOCK);
        return new ActionTarget(catalog.paperId(), catalog.documentHash(), sourceObjectId, page,
                actionLocators.stream().map(SourceLocator::locatorId).toList(),
                actionLocators.stream().flatMap(locator -> locator.rects().stream()).toList(),
                targetText, precision);
    }
}
