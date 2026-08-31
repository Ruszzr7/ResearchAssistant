package com.research.assistant.service.agent.capability;

public record AiRoleSettings(AiModelRole role, String transport, String provider, String channel,
                             String baseUrl, String apiKey, String model, String signature) { }
