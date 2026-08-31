package com.research.assistant.dto.agent;

import java.util.Map;

public record AgentActionReceiptRequest(String ticket, boolean success, Map<String, Object> actualCoordinates,
                                        String clientError) {
    public AgentActionReceiptRequest {
        actualCoordinates = actualCoordinates == null ? Map.of() : Map.copyOf(actualCoordinates);
    }
}
