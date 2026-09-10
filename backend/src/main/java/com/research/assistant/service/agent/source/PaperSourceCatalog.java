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
        for (Map.Entry<String, SourceObject> entry : objects.entrySet()) {
            String id = entry.getKey();
            SourceObject object = entry.getValue();
            if (object == null || !id.equals(object.sourceObjectId())) {
                throw new IllegalArgumentException("证据目录包含无效来源对象：" + id);
            }
        }
        for (Map.Entry<String, List<SourceLocator>> entry : locators.entrySet()) {
            if (!objects.containsKey(entry.getKey())) {
                throw new IllegalArgumentException("证据目录定位没有对应来源对象：" + entry.getKey());
            }
            if (entry.getValue().stream().anyMatch(locator ->
                    locator == null || !entry.getKey().equals(locator.sourceObjectId()))) {
                throw new IllegalArgumentException("证据目录定位与来源对象不匹配：" + entry.getKey());
            }
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
