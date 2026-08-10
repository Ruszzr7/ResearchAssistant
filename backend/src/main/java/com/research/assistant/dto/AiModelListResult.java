package com.research.assistant.dto;

import java.util.List;

/** Bounded model identifiers returned by an OpenAI-compatible models endpoint. */
public record AiModelListResult(List<String> models, int count) {
    public AiModelListResult {
        models = models == null ? List.of() : List.copyOf(models);
        count = models.size();
    }

    public AiModelListResult(List<String> models) {
        this(models, models == null ? 0 : models.size());
    }
}
