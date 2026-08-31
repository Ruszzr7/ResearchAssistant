package com.research.assistant.dto.agent;

public record AgentActionReceiptResult(String runId, String toolCallId, String status,
                                       Long annotationId, String message) { }
