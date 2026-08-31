package com.research.assistant.service.agent.runtime;

public record AgentModelSnapshot(
        String configVersion,
        String capabilitySignature,
        String snapshotJson
) {
    public AgentModelSnapshot {
        if (configVersion == null || configVersion.isBlank()) throw new IllegalArgumentException("configVersion is required");
        if (capabilitySignature == null || !capabilitySignature.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("capabilitySignature must be a SHA-256 hex string");
        }
        if (snapshotJson == null || snapshotJson.isBlank()) throw new IllegalArgumentException("snapshotJson is required");
        if (snapshotJson.toLowerCase().contains("apikey") || snapshotJson.toLowerCase().contains("api_key")) {
            throw new IllegalArgumentException("model snapshot must not contain API key fields");
        }
    }
}
