package com.research.assistant.service.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentTurnGoldenSetTest {

    private static final Set<String> REQUIRED_CATEGORIES = Set.of(
            "ORDINARY_CHAT",
            "PAPER_QUESTION",
            "GLOBAL_PAPER_QUESTION",
            "SELECTION_QUESTION",
            "FOLLOW_UP",
            "EXPLICIT_ACTION",
            "AMBIGUOUS_ACTION",
            "MULTI_TARGET_ACTION",
            "COMPOUND_ACTION",
            "INSUFFICIENT_EVIDENCE",
            "UNTRUSTED_SOURCE",
            "CONVERSATION_ISOLATION"
    );

    @Test
    void goldenSetCoversFrozenAgentInvariants() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        try (InputStream input = getClass().getResourceAsStream("/eval/agent-turn-golden.json")) {
            assertNotNull(input, "agent golden set must be packaged as a test resource");
            List<Map<String, Object>> cases = mapper.readValue(input, new TypeReference<>() { });
            assertFalse(cases.isEmpty());

            Set<String> ids = new HashSet<>();
            Set<String> categories = new HashSet<>();
            for (Map<String, Object> item : cases) {
                String id = String.valueOf(item.get("id"));
                String category = String.valueOf(item.get("category"));
                assertTrue(ids.add(id), "duplicate golden case id: " + id);
                categories.add(category);
                assertFalse(String.valueOf(item.get("message")).isBlank(), "message is required: " + id);
                assertFalse(String.valueOf(item.get("expectedPolicy")).isBlank(), "policy is required: " + id);
                if (Boolean.TRUE.equals(item.get("modification"))) {
                    assertTrue(item.containsKey("ambiguous"), "modification ambiguity must be explicit: " + id);
                }
            }

            assertEquals(REQUIRED_CATEGORIES, categories);
        }
    }
}
