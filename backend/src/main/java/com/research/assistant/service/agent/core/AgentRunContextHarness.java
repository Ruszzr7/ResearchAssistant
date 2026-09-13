package com.research.assistant.service.agent.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Per-invocation, deterministic model-view reducer. It is deliberately kept at
 * the ChatRequest boundary: LangChain4j still owns the model/tool loop and the
 * durable tool transcript is never mutated.
 */
final class AgentRunContextHarness {

    static final int SOFT_INPUT_TOKENS = 10_000;
    static final int HARD_INPUT_TOKENS = 16_000;

    private static final int MAX_COMPACT_SOURCE_CHARACTERS = 2_000;
    private static final int MAX_COMPACT_ERROR_CHARACTERS = 320;
    private static final String RECOVERY_STATE =
            "[协议恢复状态] 上一轮普通文本未被采纳；请只调用 submit_answer 提交最终答案。";

    private final ObjectMapper objectMapper;
    private final Set<String> sentToolResultKeys = new LinkedHashSet<>();
    private Set<String> preparedToolResultKeys = Set.of();
    private RequestMetrics lastMetrics;
    private RunStats runStats = RunStats.empty();

    AgentRunContextHarness(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    }

    /**
     * Prepares only the request that will be handed to the provider. The hard
     * limit is intentionally checked by the observed model after this method so
     * an over-limit attempt still produces a normal model-call trace.
     */
    synchronized ChatRequest prepare(ChatRequest candidate) {
        if (candidate == null) throw new IllegalArgumentException("chat request is required");
        int before = estimatedRequestTokens(candidate);
        boolean overSoftTarget = before > SOFT_INPUT_TOKENS;
        CompactionState compaction = new CompactionState(sentToolResultKeys);
        List<ChatMessage> messages = candidate.messages() == null
                ? List.of() : List.copyOf(candidate.messages());
        if (overSoftTarget) messages = compactMessages(messages, compaction);
        messages = compactRecoveryMessages(messages, compaction);

        ChatRequest prepared = candidate.toBuilder().messages(messages).build();
        int after = estimatedRequestTokens(prepared);
        boolean compacted = compaction.changed;
        int cumulativeEstimated = runStats.cumulativeEstimatedPromptTokens() + after;
        int maxEstimated = Math.max(runStats.maxEstimatedPromptTokens(), after);
        int initial = runStats.initialPromptTokens() == 0 && lastMetrics == null ? before
                : runStats.initialPromptTokens();
        runStats = new RunStats(initial, cumulativeEstimated, runStats.cumulativePromptTokens(),
                maxEstimated, runStats.maxPromptTokens());
        preparedToolResultKeys = resultKeys(candidate.messages());
        lastMetrics = new RequestMetrics(before, after, compacted, compaction.changedMessages,
                compaction.compactedSources, compaction.unconsumedResults, runStats);
        return prepared;
    }

    /** Marks the prepared request as sent. A request rejected locally is not consumed. */
    synchronized void markRequestSent() {
        sentToolResultKeys.clear();
        sentToolResultKeys.addAll(preparedToolResultKeys);
    }

    synchronized RequestMetrics lastMetrics() {
        return lastMetrics;
    }

    synchronized RunStats recordActualPromptTokens(int promptTokens) {
        int actual = Math.max(0, promptTokens);
        int cumulative = runStats.cumulativePromptTokens() + actual;
        int max = Math.max(runStats.maxPromptTokens(), actual);
        runStats = new RunStats(runStats.initialPromptTokens(), runStats.cumulativeEstimatedPromptTokens(),
                cumulative, runStats.maxEstimatedPromptTokens(), max);
        if (lastMetrics != null) lastMetrics = lastMetrics.withStats(runStats);
        return runStats;
    }

    synchronized RunStats runStats() {
        return runStats;
    }

    static int estimatedRequestTokens(ChatRequest request) {
        if (request == null) return 0;
        int tokens = request.messages() == null ? 0
                : request.messages().stream().mapToInt(AgentRunContextHarness::estimatedMessageTokens).sum();
        if (request.toolSpecifications() != null) {
            tokens += request.toolSpecifications().stream()
                    .mapToInt(specification -> estimatedTextTokens(specification.toString())).sum();
        }
        return tokens + (request.messages() == null ? 0 : request.messages().size() * 8);
    }

    private List<ChatMessage> compactMessages(List<ChatMessage> messages, CompactionState state) {
        if (messages.isEmpty()) return messages;
        boolean paperReadResultExists = messages.stream()
                .filter(ToolExecutionResultMessage.class::isInstance)
                .map(ToolExecutionResultMessage.class::cast)
                .anyMatch(this::containsOriginalPaperText);
        Map<String, Activation> activations = activations(messages);
        Set<String> usedActivations = usedActivationIds(messages, activations);
        List<ChatMessage> result = new ArrayList<>(messages);

        // Index all newly produced results before compacting older ones. This
        // lets an older duplicate collapse to the still-unconsumed result's
        // stable source handle/body without touching the newest result.
        for (int index = 0; index < messages.size(); index++) {
            ChatMessage message = messages.get(index);
            if (!(message instanceof ToolExecutionResultMessage toolResult)) continue;
            String resultKey = resultKey(toolResult, index);
            if (state.sentResultKeys.contains(resultKey)) continue;
            state.unconsumedResults++;
            registerSourceBodies(toolResult.text(), state);
        }

        for (int index = 0; index < messages.size(); index++) {
            ChatMessage message = messages.get(index);
            if (!(message instanceof ToolExecutionResultMessage toolResult)) continue;

            String resultKey = resultKey(toolResult, index);
            boolean consumed = state.sentResultKeys.contains(resultKey);
            if (!consumed) {
                continue;
            }

            String replacement = null;
            if ("activate_skill".equals(toolResult.toolName())) {
                if (!usedActivations.contains(toolResult.id())) continue;
                Activation activation = activations.get(toolResult.id());
                replacement = compactActivation(activation == null ? "" : activation.skillName());
            } else if (PaperOverviewToolRegistry.TOOL_NAME.equals(toolResult.toolName())
                    && paperReadResultExists) {
                replacement = compactProfileResult(toolResult.text());
            } else if (PaperOverviewToolRegistry.TOOL_NAME.equals(toolResult.toolName())) {
                // The profile remains available until original paper text has
                // been obtained; a generic status would lose planning input.
                continue;
            } else if (isPaperReadTool(toolResult.toolName())) {
                replacement = compactEvidenceResult(toolResult.text(), state);
            } else {
                replacement = compactGenericResult(toolResult.toolName(), toolResult.text());
            }

            if (replacement != null && !replacement.equals(toolResult.text())) {
                result.set(index, ToolExecutionResultMessage.builder()
                        .id(toolResult.id())
                        .toolName(toolResult.toolName())
                        .text(replacement)
                        .isError(toolResult.isError())
                        .attributes(toolResult.attributes())
                        .build());
                state.changed = true;
                state.changedMessages++;
            }
        }
        return List.copyOf(result);
    }

    /**
     * A direct prose response is only compacted when it is immediately followed
     * by the one recovery request. This leaves ordinary historical assistant
     * messages alone while preventing the long prose from being replayed.
     */
    private List<ChatMessage> compactRecoveryMessages(List<ChatMessage> messages, CompactionState state) {
        if (messages.size() < 2) return messages;
        List<ChatMessage> result = new ArrayList<>(messages);
        for (int index = 1; index < messages.size(); index++) {
            if (!(messages.get(index) instanceof UserMessage user) || !isRecoveryMessage(user)) continue;
            if (!(messages.get(index - 1) instanceof AiMessage assistant)
                    || assistant.hasToolExecutionRequests() || assistant.text() == null
                    || assistant.text().isBlank()) continue;
            result.set(index - 1, assistant.toBuilder().text(RECOVERY_STATE).build());
            state.changed = true;
            state.changedMessages++;
        }
        return List.copyOf(result);
    }

    private String compactEvidenceResult(String raw, CompactionState state) {
        JsonNode root = readObject(raw);
        if (root == null) return compactGenericResult("paper-evidence", raw);

        ObjectNode output = objectMapper.createObjectNode();
        String status = root.path("status").asText("").trim();
        boolean success = !Set.of("invalid_request", "error", "unavailable", "stale").contains(status);
        output.put("status", status.isBlank() ? "success" : status);
        output.put("success", success);

        ArrayNode sources = output.putArray("sources");
        JsonNode sourceArray = root.path("sources");
        if (sourceArray.isArray()) {
            for (JsonNode source : sourceArray) {
                if (!source.isObject()) continue;
                String sourceId = source.path("sourceObjectId").asText("").trim();
                if (sourceId.isBlank()) continue;
                ObjectNode compact = sources.addObject();
                compact.put("sourceObjectId", sourceId);
                copyText(source, compact, "contentType");
                copyNumber(source, compact, "page");
                copyText(source, compact, "formulaNumber");
                copyBoolean(source, compact, "textReliable");
                String body = firstText(source, "content", "fullText");
                boolean hadSource = state.sourceBodiesById.containsKey(sourceId);
                if (hadSource) {
                    compact.put("sameSource", true);
                } else {
                    state.sourceBodiesById.put(sourceId, body);
                    if (!body.isBlank()) {
                        String normalized = normalizeBody(body);
                        String owner = state.sourceOwnerByBody.putIfAbsent(normalized, sourceId);
                        if (owner != null && !owner.equals(sourceId)) {
                            compact.put("sameContentAs", owner);
                        } else {
                            boolean complete = source.path("contentComplete").asBoolean(true);
                            String bounded = compactText(body);
                            compact.put("content", bounded);
                            if (!complete || !bounded.equals(body)) compact.put("contentComplete", false);
                            else compact.put("contentComplete", true);
                        }
                    } else if (source.has("contentComplete")) {
                        compact.put("contentComplete", source.path("contentComplete").asBoolean(false));
                    }
                }
                state.compactedSources++;
            }
        }

        ArrayNode needs = output.putArray("needs");
        JsonNode evidenceNeeds = root.path("evidenceNeeds");
        if (evidenceNeeds.isArray()) {
            for (JsonNode need : evidenceNeeds) {
                if (!need.isObject()) continue;
                String needId = need.path("needId").asText("").trim();
                if (needId.isBlank() || !state.seenNeedIds.add(needId)) {
                    if (!needId.isBlank()) {
                        int cursor = need.path("nextCursor").asInt(-1);
                        if (cursor >= 0) state.latestCursors.put(needId, cursor);
                    }
                    continue;
                }
                ObjectNode compactNeed = needs.addObject();
                compactNeed.put("needId", needId);
                ArrayNode ids = compactNeed.putArray("sourceObjectIds");
                copyUniqueStrings(need.path("sourceObjectIds"), ids);
                copyText(need, compactNeed, "retrievalStatus");
                copyBoolean(need, compactNeed, "hasMore");
                int cursor = need.path("nextCursor").asInt(-1);
                if (cursor >= 0) {
                    compactNeed.put("nextCursor", cursor);
                    state.latestCursors.put(needId, cursor);
                }
            }
        }
        if (!state.latestCursors.isEmpty()) {
            ObjectNode cursors = output.putObject("cursorUpdates");
            state.latestCursors.forEach(cursors::put);
        }

        JsonNode visuals = root.path("visualSources");
        if (visuals.isArray()) {
            ArrayNode compactVisuals = output.putArray("visualSources");
            for (JsonNode visual : visuals) {
                if (!visual.isObject()) continue;
                ObjectNode item = compactVisuals.addObject();
                copyText(visual, item, "sourceObjectId");
                copyNumber(visual, item, "page");
                copyText(visual, item, "contentType");
            }
        }
        copyBoolean(root, output, "visualUnavailable");

        if (!success) copyIssues(root, output);
        return write(output);
    }

    private String compactProfileResult(String raw) {
        JsonNode root = readObject(raw);
        ObjectNode output = objectMapper.createObjectNode();
        String status = root == null ? "consumed" : root.path("status").asText("consumed");
        boolean success = !Set.of("unavailable", "stale", "error").contains(status);
        output.put("status", status);
        output.put("success", success);
        output.put("message", "论文画像已读取并消费；仅用于规划，不能作为最终论文证据。");
        return write(output);
    }

    private boolean containsOriginalPaperText(ToolExecutionResultMessage message) {
        if (!isPaperReadTool(message.toolName())) return false;
        JsonNode root = readObject(message.text());
        if (root == null || !root.path("sources").isArray()) return false;
        for (JsonNode source : root.path("sources")) {
            if (source.isObject() && !firstText(source, "content", "fullText").isBlank()) return true;
        }
        return false;
    }

    private String compactActivation(String skillName) {
        ObjectNode output = objectMapper.createObjectNode();
        output.put("status", "activated");
        output.put("success", true);
        output.put("message", skillName == null || skillName.isBlank()
                ? "Skill 已激活；完整说明已消费，后续仅保留其工具能力。"
                : "Skill 已激活：" + skillName + "；完整说明已消费，后续仅保留其工具能力。");
        return write(output);
    }

    private String compactGenericResult(String toolName, String raw) {
        JsonNode root = readObject(raw);
        ObjectNode output = objectMapper.createObjectNode();
        boolean error = root != null && (root.has("error")
                || Set.of("error", "invalid_request", "unavailable", "stale").contains(
                root.path("status").asText("")));
        output.put("status", error ? "error" : "consumed");
        output.put("success", !error);
        if (toolName != null && !toolName.isBlank()) output.put("tool", toolName);
        if (error) {
            String message = root == null ? raw : firstText(root, "error", "message");
            if (!message.isBlank()) output.put("message", bound(message, MAX_COMPACT_ERROR_CHARACTERS));
            if (root != null) copyBoolean(root, output, "retryable");
            if (root != null) copyIssues(root, output);
        }
        return write(output);
    }

    private void registerSourceBodies(String raw, CompactionState state) {
        JsonNode root = readObject(raw);
        if (root == null || !root.path("sources").isArray()) return;
        for (JsonNode source : root.path("sources")) {
            if (!source.isObject()) continue;
            String sourceId = source.path("sourceObjectId").asText("").trim();
            String body = firstText(source, "content", "fullText");
            if (sourceId.isBlank() || body.isBlank()) continue;
            state.sourceBodiesById.putIfAbsent(sourceId, body);
            state.sourceOwnerByBody.putIfAbsent(normalizeBody(body), sourceId);
        }
    }

    private Map<String, Activation> activations(List<ChatMessage> messages) {
        Map<String, Activation> result = new LinkedHashMap<>();
        for (int index = 0; index < messages.size(); index++) {
            if (!(messages.get(index) instanceof AiMessage assistant)
                    || assistant.toolExecutionRequests() == null) continue;
            for (ToolExecutionRequest request : assistant.toolExecutionRequests()) {
                if (!"activate_skill".equals(request.name())) continue;
                result.put(request.id(), new Activation(index, skillName(request)));
            }
        }
        return result;
    }

    private Set<String> usedActivationIds(List<ChatMessage> messages, Map<String, Activation> activations) {
        if (activations.isEmpty()) return Set.of();
        List<Activation> ordered = activations.values().stream()
                .sorted(java.util.Comparator.comparingInt(Activation::messageIndex)).toList();
        Set<String> used = new HashSet<>();
        for (int activationIndex = 0; activationIndex < ordered.size(); activationIndex++) {
            Activation activation = ordered.get(activationIndex);
            int nextActivation = activationIndex + 1 < ordered.size()
                    ? ordered.get(activationIndex + 1).messageIndex() : messages.size();
            for (int index = activation.messageIndex() + 1; index < nextActivation; index++) {
                if (!(messages.get(index) instanceof ToolExecutionResultMessage result)
                        || "activate_skill".equals(result.toolName())) continue;
                if (isSkillTool(activation.skillName(), result.toolName())) {
                    // The result id is the activation request id; the actual
                    // scoped tool can have a different id.
                    used.add(findActivationId(activations, activation));
                    break;
                }
            }
        }
        return used;
    }

    private static String findActivationId(Map<String, Activation> activations, Activation value) {
        return activations.entrySet().stream()
                .filter(entry -> entry.getValue() == value)
                .map(Map.Entry::getKey).findFirst().orElse("");
    }

    private static boolean isSkillTool(String skillName, String toolName) {
        if (toolName == null) return false;
        if ("paper-profile".equals(skillName)) return PaperOverviewToolRegistry.TOOL_NAME.equals(toolName);
        if ("paper-evidence".equals(skillName)) {
            return "retrieve_paper_evidence".equals(toolName) || "read_pages".equals(toolName);
        }
        if ("paper-action".equals(skillName)) return "paper_action".equals(toolName);
        // Unknown Skill bindings are intentionally conservative: without the
        // binding here we cannot prove that an arbitrary tool belongs to it.
        return false;
    }

    private String skillName(ToolExecutionRequest request) {
        try {
            return objectMapper.readTree(request.arguments()).path("skill_name").asText("").trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static boolean isPaperReadTool(String name) {
        return "retrieve_paper_evidence".equals(name) || "read_pages".equals(name);
    }

    private static Set<String> resultKeys(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) return Set.of();
        Set<String> result = new LinkedHashSet<>();
        for (int index = 0; index < messages.size(); index++) {
            if (messages.get(index) instanceof ToolExecutionResultMessage tool) {
                result.add(resultKey(tool, index));
            }
        }
        return result;
    }

    private static String resultKey(ToolExecutionResultMessage message, int index) {
        if (message.id() != null && !message.id().isBlank()) return "id:" + message.id();
        String toolName = message.toolName() == null ? "" : message.toolName();
        String text = message.text() == null ? "" : message.text();
        return "fallback:" + index + ":" + toolName + ":" + text.hashCode();
    }

    private static int estimatedMessageTokens(ChatMessage message) {
        if (message instanceof SystemMessage system) return estimatedTextTokens(system.text());
        if (message instanceof UserMessage user) {
            int tokens = 0;
            for (Content content : user.contents()) {
                if (content instanceof TextContent text) tokens += estimatedTextTokens(text.text());
                else if (content instanceof ImageContent) tokens += 1_200;
                else tokens += 1_200;
            }
            return tokens;
        }
        if (message instanceof AiMessage assistant) {
            int tokens = estimatedTextTokens(assistant.text());
            if (assistant.toolExecutionRequests() != null) {
                tokens += assistant.toolExecutionRequests().stream()
                        .mapToInt(request -> estimatedTextTokens(request.toString())).sum();
            }
            return tokens;
        }
        if (message instanceof ToolExecutionResultMessage tool) return estimatedTextTokens(tool.text());
        return estimatedTextTokens(message == null ? "" : message.toString());
    }

    private static int estimatedTextTokens(String value) {
        if (value == null || value.isBlank()) return 0;
        int ascii = 0;
        int nonAscii = 0;
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) <= 0x7f) ascii++;
            else nonAscii++;
        }
        return (int) Math.ceil(ascii / 3.2d + nonAscii);
    }

    private static boolean isRecoveryMessage(UserMessage message) {
        return message.contents().stream().filter(TextContent.class::isInstance)
                .map(TextContent.class::cast).map(TextContent::text)
                .anyMatch(text -> text != null && text.contains("[协议恢复]"));
    }

    private JsonNode readObject(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            JsonNode node = objectMapper.readTree(raw);
            return node != null && node.isObject() ? node : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private String write(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception error) {
            throw new IllegalStateException("failed to compact agent context", error);
        }
    }

    private static void copyText(JsonNode source, ObjectNode target, String field) {
        String value = source.path(field).asText("").trim();
        if (!value.isBlank()) target.put(field, value);
    }

    private static void copyNumber(JsonNode source, ObjectNode target, String field) {
        JsonNode value = source.get(field);
        if (value != null && value.isNumber()) target.set(field, value);
    }

    private static void copyBoolean(JsonNode source, ObjectNode target, String field) {
        JsonNode value = source.get(field);
        if (value != null && value.isBoolean()) target.set(field, value);
    }

    private static String firstText(JsonNode source, String... fields) {
        for (String field : fields) {
            String value = source.path(field).asText("");
            if (!value.isBlank()) return value;
        }
        return "";
    }

    private static void copyUniqueStrings(JsonNode source, ArrayNode target) {
        if (!source.isArray()) return;
        Set<String> values = new LinkedHashSet<>();
        for (JsonNode value : source) {
            String text = value.asText("").trim();
            if (!text.isBlank() && values.add(text)) target.add(text);
        }
    }

    private static void copyIssues(JsonNode source, ObjectNode target) {
        JsonNode issues = source.path("issues");
        if (!issues.isArray()) return;
        ArrayNode compact = target.putArray("issues");
        for (JsonNode issue : issues) {
            if (!issue.isObject()) continue;
            ObjectNode item = compact.addObject();
            copyText(issue, item, "needId");
            copyText(issue, item, "field");
            copyText(issue, item, "code");
            String message = issue.path("message").asText("").trim();
            if (!message.isBlank()) item.put("message", bound(message, MAX_COMPACT_ERROR_CHARACTERS));
        }
    }

    private static String compactText(String value) {
        if (value == null || value.length() <= MAX_COMPACT_SOURCE_CHARACTERS) return value == null ? "" : value;
        int half = (MAX_COMPACT_SOURCE_CHARACTERS - 1) / 2;
        return value.substring(0, half) + "…" + value.substring(value.length() - half);
    }

    private static String normalizeBody(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private static String bound(String value, int max) {
        if (value == null || value.length() <= max) return value == null ? "" : value;
        return value.substring(0, max - 1) + "…";
    }

    record RequestMetrics(int estimatedPromptTokensBefore, int estimatedPromptTokensAfter,
                          boolean compacted, int compactedMessageCount, int compactedSourceCount,
                          int unconsumedToolResultCount, RunStats runStats) {
        RequestMetrics withStats(RunStats replacement) {
            return new RequestMetrics(estimatedPromptTokensBefore, estimatedPromptTokensAfter, compacted,
                    compactedMessageCount, compactedSourceCount, unconsumedToolResultCount, replacement);
        }
    }

    record RunStats(int initialPromptTokens, int cumulativeEstimatedPromptTokens,
                    int cumulativePromptTokens, int maxEstimatedPromptTokens, int maxPromptTokens) {
        static RunStats empty() { return new RunStats(0, 0, 0, 0, 0); }
    }

    private record Activation(int messageIndex, String skillName) { }

    private static final class CompactionState {
        private final Set<String> sentResultKeys;
        private final Map<String, String> sourceBodiesById = new LinkedHashMap<>();
        private final Map<String, String> sourceOwnerByBody = new LinkedHashMap<>();
        private final Set<String> seenNeedIds = new LinkedHashSet<>();
        private final Map<String, Integer> latestCursors = new LinkedHashMap<>();
        private boolean changed;
        private int changedMessages;
        private int compactedSources;
        private int unconsumedResults;

        private CompactionState(Set<String> sentResultKeys) {
            this.sentResultKeys = sentResultKeys == null ? Set.of() : Set.copyOf(sentResultKeys);
        }
    }
}
