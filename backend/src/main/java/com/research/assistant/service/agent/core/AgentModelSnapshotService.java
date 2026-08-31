package com.research.assistant.service.agent.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.service.SettingsService;
import com.research.assistant.service.agent.runtime.AgentModelSnapshot;
import com.research.assistant.service.ai.LangChain4jModelFactory;
import com.research.assistant.service.ai.provider.AiProviderProfile;
import org.springframework.stereotype.Service;
import com.research.assistant.service.agent.capability.AiRoleSettingsService;
import com.research.assistant.service.agent.capability.AiModelRole;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class AgentModelSnapshotService {
    private final SettingsService settingsService;
    private final LangChain4jModelFactory modelFactory;
    private final ObjectMapper objectMapper;
    private final AiRoleSettingsService roleSettingsService;

    public AgentModelSnapshotService(SettingsService settingsService, LangChain4jModelFactory modelFactory,
                                     ObjectMapper objectMapper, AiRoleSettingsService roleSettingsService) {
        this.settingsService = settingsService;
        this.modelFactory = modelFactory;
        this.objectMapper = objectMapper;
        this.roleSettingsService = roleSettingsService;
    }

    public AgentModelSnapshot current() {
        try {
            AiProviderProfile profile = modelFactory.currentProfile();
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("provider", profile.providerValue());
            snapshot.put("channel", profile.channel());
            snapshot.put("baseUrl", profile.baseUrl());
            snapshot.put("model", settingsService.getValue("model"));
            snapshot.put("transport", "OPENAI_COMPATIBLE");
            snapshot.put("agentProtocol", "NATIVE_TOOL_CALLING_V1");
            String json = objectMapper.writeValueAsString(snapshot);
            String signature = roleSettingsService.resolve(AiModelRole.CHAT).signature();
            return new AgentModelSnapshot("chat-" + signature.substring(0, 16), signature, json);
        } catch (Exception error) {
            throw new IllegalStateException("failed to snapshot chat model configuration", error);
        }
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
