package com.research.assistant.service.agent.source;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record PaperSourceCatalog(
        long paperId,
        String documentHash,
        String parserVersion,
        int pageCount,
        Map<String, SourceObject> objects,
        Map<String, List<SourceLocator>> locators
) {
    public PaperSourceCatalog {
        objects = objects == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(objects));
        if (locators == null) {
            locators = Map.of();
        } else {
            Map<String, List<SourceLocator>> copied = new LinkedHashMap<>();
            locators.forEach((key, value) -> copied.put(key, value == null ? List.of() : List.copyOf(value)));
            locators = Map.copyOf(copied);
        }
    }

    public SourceObject requireObject(String sourceObjectId) {
        SourceObject object = objects.get(sourceObjectId);
        if (object == null) throw new IllegalArgumentException("找不到来源对象：" + sourceObjectId);
        return object;
    }

    public List<SourceLocator> requireLocators(String sourceObjectId) {
        List<SourceLocator> value = locators.get(sourceObjectId);
        if (value == null || value.isEmpty()) throw new IllegalArgumentException("找不到来源定位：" + sourceObjectId);
        return value;
    }
}
