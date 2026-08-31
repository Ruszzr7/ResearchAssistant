package com.research.assistant.service.agent.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.entity.AgentConversationSummaryRecord;
import com.research.assistant.entity.ResearchMessage;
import com.research.assistant.mapper.AgentConversationSummaryMapper;
import com.research.assistant.mapper.AgentTurnMapper;
import com.research.assistant.mapper.ResearchMessageMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentConversationSummaryService {

    public static final String SCHEMA_VERSION = "agent-context-digest-v1";
    private static final int MAX_SUMMARY_CHARS = 32_000;
    private static final int COMPACT_AFTER_MESSAGES = 24;
    private static final int RETAIN_RAW_MESSAGES = 12;
    private static final int MAX_MESSAGE_EXCERPT_CHARS = 1_200;

    private final AgentConversationSummaryMapper summaryMapper;
    private final AgentTurnMapper turnMapper;
    private final ResearchMessageMapper messageMapper;
    private final ObjectMapper objectMapper;

    public AgentConversationSummaryService(AgentConversationSummaryMapper summaryMapper,
                                           AgentTurnMapper turnMapper,
                                           ResearchMessageMapper messageMapper,
                                           ObjectMapper objectMapper) {
        this.summaryMapper = summaryMapper;
        this.turnMapper = turnMapper;
        this.messageMapper = messageMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * Creates a deterministic rolling digest only when raw history grows beyond
     * the context threshold. It performs no external model call and retains the
     * newest messages verbatim for the Agent.
     */
    @Transactional
    public AgentConversationSummaryRecord compactIfNeeded(long sessionId) {
        AgentConversationSummaryRecord latest = summaryMapper.selectLatest(sessionId);
        long boundary = latest == null || latest.getCoveredThroughMessageId() == null
                ? 0 : latest.getCoveredThroughMessageId();
        java.util.List<ResearchMessage> pending = messageMapper.selectFinalAfter(sessionId, boundary);
        if (pending == null || pending.size() <= COMPACT_AFTER_MESSAGES) return latest;

        int compactCount = pending.size() - RETAIN_RAW_MESSAGES;
        java.util.List<ResearchMessage> compacted = pending.subList(0, compactCount);
        StringBuilder digest = new StringBuilder(previousDigest(latest));
        for (ResearchMessage message : compacted) {
            if (digest.length() > 0) digest.append('\n');
            digest.append("- ").append(normalizedRole(message.getRole())).append(": ")
                    .append(excerpt(message.getContent()));
        }
        String value = tail(digest.toString(), MAX_SUMMARY_CHARS - 128);
        try {
            String json = objectMapper.writeValueAsString(java.util.Map.of("digest", value));
            return save(sessionId, compacted.get(compacted.size() - 1).getId(), SCHEMA_VERSION, json);
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new IllegalStateException("failed to serialize conversation digest", error);
        }
    }

    @Transactional
    public AgentConversationSummaryRecord save(long sessionId,
                                               Long coveredThroughMessageId,
                                               String schemaVersion,
                                               String summaryJson) {
        if (turnMapper.lockSession(sessionId) == null) {
            throw new IllegalArgumentException("research session not found: " + sessionId);
        }
        if (coveredThroughMessageId != null) {
            ResearchMessage message = messageMapper.selectById(coveredThroughMessageId);
            if (message == null || message.getSessionId() != sessionId) {
                throw new IllegalArgumentException("summary boundary message does not belong to session");
            }
        }
        String schema = requireText(schemaVersion, "schemaVersion");
        String json = requireText(summaryJson, "summaryJson");
        if (json.length() > MAX_SUMMARY_CHARS) throw new IllegalArgumentException("summary exceeds character limit");

        AgentConversationSummaryRecord latest = summaryMapper.selectLatest(sessionId);
        AgentConversationSummaryRecord record = new AgentConversationSummaryRecord();
        record.setSessionId(sessionId);
        record.setRevision(latest == null ? 1 : latest.getRevision() + 1);
        record.setSchemaVersion(schema);
        record.setCoveredThroughMessageId(coveredThroughMessageId);
        record.setSummaryJson(json);
        summaryMapper.insert(record);
        return record;
    }

    public AgentConversationSummaryRecord latest(long sessionId) {
        return summaryMapper.selectLatest(sessionId);
    }

    private String previousDigest(AgentConversationSummaryRecord latest) {
        if (latest == null || latest.getSummaryJson() == null || latest.getSummaryJson().isBlank()) return "";
        try {
            JsonNode root = objectMapper.readTree(latest.getSummaryJson());
            return root.path("digest").asText("");
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String normalizedRole(String role) {
        return "ASSISTANT".equalsIgnoreCase(role) ? "Assistant" : "User";
    }

    private static String excerpt(String content) {
        if (content == null || content.isBlank()) return "(empty)";
        String normalized = content.replaceAll("\\s+", " ").trim();
        return normalized.length() <= MAX_MESSAGE_EXCERPT_CHARS
                ? normalized : normalized.substring(0, MAX_MESSAGE_EXCERPT_CHARS) + "…";
    }

    private static String tail(String value, int maxChars) {
        if (value.length() <= maxChars) return value;
        return "…\n" + value.substring(value.length() - maxChars + 2);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }
}
