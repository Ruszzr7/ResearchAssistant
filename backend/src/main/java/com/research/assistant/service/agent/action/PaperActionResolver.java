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
        if (locators.stream().anyMatch(locator -> locator.pageNumber() != page)) {
            throw new IllegalArgumentException("操作目标跨越多个页面，需要先澄清目标");
        }
        String targetText = locators.stream().map(SourceLocator::targetText)
                .filter(text -> text != null && !text.isBlank())
                .collect(java.util.stream.Collectors.joining("\n"));
        com.research.assistant.service.pdf.layout.EvidenceLocator.Precision precision = locators.stream()
                .map(SourceLocator::precision)
                .findFirst()
                .orElse(com.research.assistant.service.pdf.layout.EvidenceLocator.Precision.BLOCK);
        return new ActionTarget(catalog.paperId(), catalog.documentHash(), sourceObjectId, page,
                locators.stream().map(SourceLocator::locatorId).toList(),
                locators.stream().flatMap(locator -> locator.rects().stream()).toList(),
                targetText, precision);
    }
}
