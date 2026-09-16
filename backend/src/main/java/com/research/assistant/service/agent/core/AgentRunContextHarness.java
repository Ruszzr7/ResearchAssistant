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
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Single owner of the provider-bound context budget. The durable LangChain4j
 * transcript stays intact; every request is projected by the same rules,
 * independently of whether a result has already been sent.
 */
final class AgentRunContextHarness {

    static final int INPUT_BUDGET_TOKENS = 16_000;
    private static final List<ProjectionPolicy> POLICIES = List.of(
            new ProjectionPolicy(8, 900), new ProjectionPolicy(6, 600),
            new ProjectionPolicy(4, 360), new ProjectionPolicy(2, 220));
    private static final int MAX_PROFILE_TEXT = 480;
    private static final int MAX_PROFILE_ARRAY = 6;
    private static final int MAX_ERROR_TEXT = 320;
    private static final int MAX_PROTOCOL_TEXT = 600;
    private static final int RECENT_TOOL_EXCHANGES = 2;
    private static final int MAX_WORKING_STATE_SOURCES = 16;
    private static final int MAX_WORKING_STATE_SOURCE_TEXT = 640;
    private static final int MAX_WORKING_STATE_TEXT = 12_000;

    private final ObjectMapper objectMapper;
    private RequestMetrics lastMetrics;
    private RunStats runStats = RunStats.empty();

    AgentRunContextHarness(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    }

    synchronized ChatRequest prepare(ChatRequest candidate) {
        if (candidate == null) throw new IllegalArgumentException("chat request is required");
        int before = estimatedRequestTokens(candidate);
        PreparedView selected = null;
        for (ProjectionPolicy policy : POLICIES) {
            PreparedView attempt = prepareView(candidate, policy);
            selected = attempt;
            if (attempt.tokens() <= INPUT_BUDGET_TOKENS) break;
        }
        if (selected == null || selected.tokens() > INPUT_BUDGET_TOKENS) {
            throw new IllegalArgumentException("CONTEXT_BUDGET_EXCEEDED");
        }
        int initial = runStats.initialPromptTokens() == 0 && lastMetrics == null
                ? before : runStats.initialPromptTokens();
        runStats = new RunStats(initial,
                runStats.cumulativeEstimatedPromptTokens() + selected.tokens(),
                runStats.cumulativePromptTokens(),
                Math.max(runStats.maxEstimatedPromptTokens(), selected.tokens()),
                runStats.maxPromptTokens());
        lastMetrics = new RequestMetrics(before, selected.tokens(), selected.changed(),
                selected.changedMessages(), selected.projectedSources(), 0, runStats);
        return selected.request();
    }

    private PreparedView prepareView(ChatRequest candidate, ProjectionPolicy policy) {
        ProjectionState state = new ProjectionState(policy.bodyCards(), policy.bodyCharacters());
        // Build the fold from the unprojected results. The ordinary model view
        // may omit old source bodies to fit the request budget; folding that view
        // would make the Agent forget evidence and repeat the same read.
        List<ChatMessage> projected = projectToolResults(candidate.messages(), state);
        projected = foldConsumedToolExchanges(projected, candidate.messages(), state);
        ChatRequest request = candidate.toBuilder().messages(projected).build();
        if (estimatedRequestTokens(request) > INPUT_BUDGET_TOKENS) {
            projected = dropOldestConversationTurns(candidate, projected, state);
            request = candidate.toBuilder().messages(projected).build();
        }
        return new PreparedView(request, estimatedRequestTokens(request), state.changed,
                state.changedMessages, state.projectedSources);
    }

    private List<ChatMessage> projectToolResults(List<ChatMessage> input, ProjectionState state) {
        if (input == null || input.isEmpty()) return List.of();
        List<ChatMessage> result = new ArrayList<>(input);
        Map<String, Activation> activations = activations(input);
        Set<String> usedActivations = usedActivationIds(input, activations);
        for (int index = input.size() - 1; index >= 0; index--) {
            if (!(input.get(index) instanceof ToolExecutionResultMessage tool)) continue;
            String replacement = null;
            if ("activate_skill".equals(tool.toolName())) {
                if (usedActivations.contains(tool.id())) {
                    Activation activation = activations.get(tool.id());
                    replacement = activationResult(activation == null ? "" : activation.skillName());
                }
            } else if (PaperOverviewToolRegistry.TOOL_NAME.equals(tool.toolName())) {
                replacement = profileResult(tool.text());
            } else if (isPaperReadTool(tool.toolName())) {
                replacement = evidenceResult(tool.text(), state);
            } else {
                replacement = genericResult(tool.toolName(), tool.text());
            }
            if (replacement == null || replacement.equals(tool.text())) continue;
            result.set(index, ToolExecutionResultMessage.builder().id(tool.id()).toolName(tool.toolName())
                    .text(replacement).isError(tool.isError()).attributes(tool.attributes()).build());
            state.changed = true;
            state.changedMessages++;
        }
        return List.copyOf(result);
    }

    /**
     * A tool loop is a working session, not an ever-growing chat transcript. Keep
     * the two newest exchanges verbatim so the model can react to their exact
     * result, and fold older completed exchanges into one deterministic tool
     * state. This preserves the Agent's observations without prescribing which
     * capability it must use next.
     */
    private List<ChatMessage> foldConsumedToolExchanges(List<ChatMessage> messages,
                                                         List<ChatMessage> sourceMessages,
                                                         ProjectionState state) {
        int turnStart = currentTurnStart(messages);
        if (turnStart < 0) return messages;
        List<ToolExchange> exchanges = completedToolExchanges(messages, turnStart);
        if (exchanges.size() <= RECENT_TOOL_EXCHANGES) return messages;

        List<ToolExchange> consumed = exchanges.subList(0, exchanges.size() - RECENT_TOOL_EXCHANGES);
        Set<Integer> removed = new LinkedHashSet<>();
        consumed.forEach(exchange -> {
            for (int index = exchange.start(); index <= exchange.end(); index++) removed.add(index);
        });
        String workingState = workingState(sourceMessages, consumed);
        if (workingState == null || workingState.isBlank()) return messages;

        int insertion = consumed.get(0).start();
        List<ChatMessage> result = new ArrayList<>(messages.size() - removed.size() + 2);
        for (int index = 0; index < messages.size(); index++) {
            if (index == insertion) {
                String id = "agent-working-state";
                result.add(AiMessage.from(ToolExecutionRequest.builder().id(id)
                        .name("agent_working_state").arguments("{}").build()));
                result.add(ToolExecutionResultMessage.builder().id(id).toolName("agent_working_state")
                        .text(workingState).build());
            }
            if (!removed.contains(index)) result.add(messages.get(index));
        }
        state.changed = true;
        state.changedMessages += removed.size();
        return List.copyOf(result);
    }

    private List<ToolExchange> completedToolExchanges(List<ChatMessage> messages, int turnStart) {
        List<ToolExchange> result = new ArrayList<>();
        for (int index = Math.max(0, turnStart); index < messages.size(); index++) {
            if (!(messages.get(index) instanceof AiMessage assistant)
                    || assistant.toolExecutionRequests() == null
                    || assistant.toolExecutionRequests().isEmpty()) continue;
            Set<String> ids = new LinkedHashSet<>();
            assistant.toolExecutionRequests().forEach(request -> ids.add(request.id()));
            int end = index;
            Set<String> answered = new LinkedHashSet<>();
            for (int cursor = index + 1; cursor < messages.size(); cursor++) {
                if (messages.get(cursor) instanceof ToolExecutionResultMessage tool
                        && ids.contains(tool.id())) {
                    answered.add(tool.id());
                    end = cursor;
                    continue;
                }
                break;
            }
            if (!ids.isEmpty() && answered.containsAll(ids)) result.add(new ToolExchange(index, end));
        }
        return result;
    }

    private String workingState(List<ChatMessage> messages, List<ToolExchange> exchanges) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("status", "active");
        root.put("description", "已完成工具调用的确定性工作状态；内容仍按原工具结果的数据权限处理。");
        Set<String> skills = new LinkedHashSet<>();
        JsonNode profile = null;
        Map<String, JsonNode> sources = new LinkedHashMap<>();
        Map<String, JsonNode> needs = new LinkedHashMap<>();
        ArrayNode events = objectMapper.createArrayNode();

        for (ToolExchange exchange : exchanges) {
            for (int index = exchange.start(); index <= exchange.end(); index++) {
                if (!(messages.get(index) instanceof ToolExecutionResultMessage tool)) continue;
                JsonNode value = readObject(tool.text());
                if (value == null) continue;
                if ("activate_skill".equals(tool.toolName())) {
                    String skill = value.path("skill").asText("").trim();
                    if (!skill.isBlank()) skills.add(skill);
                    continue;
                }
                if (PaperOverviewToolRegistry.TOOL_NAME.equals(tool.toolName())) {
                    if (value.has("profile")) {
                        profile = compactNode(value, 0, MAX_PROFILE_ARRAY, MAX_PROFILE_TEXT);
                    }
                    continue;
                }
                if (isPaperReadTool(tool.toolName())) {
                    for (JsonNode source : value.path("sources")) {
                        String id = source.path("sourceObjectId").asText("").trim();
                        if (id.isBlank() || sources.size() >= MAX_WORKING_STATE_SOURCES) continue;
                        sources.putIfAbsent(id, compactWorkingSource(source));
                    }
                    for (JsonNode need : value.path("evidenceNeeds")) {
                        String id = need.path("needId").asText("").trim();
                        if (!id.isBlank()) needs.put(id, compactNode(need, 0, 6, 280));
                    }
                    continue;
                }
                if (events.size() < 8) {
                    ObjectNode event = events.addObject();
                    event.put("tool", tool.toolName());
                    copyText(value, event, "status");
                    copyText(value, event, "message");
                    copyNumber(value, event, "actionCount");
                }
            }
        }
        root.set("activatedSkills", objectMapper.valueToTree(skills));
        if (profile != null) root.set("paperProfile", profile);
        root.set("evidenceSources", objectMapper.valueToTree(sources.values()));
        root.set("evidenceNeeds", objectMapper.valueToTree(needs.values()));
        root.set("events", events);
        ObjectNode capabilities = root.putObject("capabilities");
        capabilities.put("profileLoaded", profile != null);
        capabilities.put("evidenceSourceCount", sources.size());
        capabilities.put("actionPlanned", events.findValuesAsText("status").contains("action_planned"));
        capabilities.put("guidance", "根据现有信息自主决定下一步；相同参数的重复调用不会产生新信息。");
        return bound(write(root), MAX_WORKING_STATE_TEXT);
    }

    private ObjectNode compactWorkingSource(JsonNode source) {
        ObjectNode card = objectMapper.createObjectNode();
        copyText(source, card, "sourceObjectId");
        copyText(source, card, "citationLabel");
        copyText(source, card, "contentType");
        copyNumber(source, card, "page");
        copyText(source, card, "formulaNumber");
        copyBoolean(source, card, "contentComplete");
        copyBoolean(source, card, "textReliable");
        copyNode(source, card, "sectionPath", 4, 120);
        String body = firstText(source, "content", "fullText");
        if (!body.isBlank()) card.put("content", bound(body, MAX_WORKING_STATE_SOURCE_TEXT));
        return card;
    }

    private List<ChatMessage> dropOldestConversationTurns(ChatRequest candidate,
                                                           List<ChatMessage> messages,
                                                           ProjectionState state) {
        int currentTurn = currentTurnStart(messages);
        if (currentTurn <= 1) return messages;
        List<HistoryTurn> turns = historicalTurns(messages, currentTurn);
        Set<Integer> removed = new LinkedHashSet<>();
        for (HistoryTurn turn : turns) {
            if (estimatedRequestTokens(candidate.toBuilder()
                    .messages(withoutIndexes(messages, removed)).build()) <= INPUT_BUDGET_TOKENS) break;
            for (int index = turn.start(); index <= turn.end(); index++) removed.add(index);
            state.changed = true;
            state.changedMessages += turn.end() - turn.start() + 1;
        }
        return removed.isEmpty() ? messages : withoutIndexes(messages, removed);
    }

    private String evidenceResult(String raw, ProjectionState state) {
        JsonNode root = readObject(raw);
        if (root == null) return genericResult("paper-evidence", raw);
        ObjectNode output = objectMapper.createObjectNode();
        copyText(root, output, "status");
        copyBoolean(root, output, "success");
        copyText(root, output, "coverageState");
        copyBoolean(root, output, "stopRecommended");
        ArrayNode sources = output.putArray("sources");
        if (root.path("sources").isArray()) {
            for (JsonNode source : root.path("sources")) {
                if (!source.isObject()) continue;
                String sourceId = source.path("sourceObjectId").asText("").trim();
                if (sourceId.isBlank()) continue;
                ObjectNode card = sources.addObject();
                card.put("sourceObjectId", sourceId);
                copyText(source, card, "citationLabel");
                copyText(source, card, "contentType");
                copyNumber(source, card, "page");
                copyText(source, card, "formulaNumber");
                copyBoolean(source, card, "textReliable");
                copyBoolean(source, card, "contentComplete");
                copyNode(source, card, "sectionPath", 4, 120);
                String body = firstText(source, "content", "fullText");
                if (!state.seenSources.add(sourceId)) {
                    card.put("sameSource", true);
                } else if (!body.isBlank() && state.remainingBodyCards > 0) {
                    card.put("content", bound(body, state.bodyCharacters));
                    if (body.length() > state.bodyCharacters) card.put("contentComplete", false);
                    state.remainingBodyCards--;
                } else if (!body.isBlank()) {
                    card.put("contentOmittedFromModelView", true);
                }
                state.projectedSources++;
            }
        }
        ArrayNode needs = output.putArray("evidenceNeeds");
        if (root.path("evidenceNeeds").isArray()) {
            for (JsonNode need : root.path("evidenceNeeds")) {
                if (!need.isObject()) continue;
                ObjectNode item = needs.addObject();
                copyText(need, item, "needId");
                copyText(need, item, "objective");
                copyText(need, item, "retrievalStatus");
                copyText(need, item, "coverageState");
                copyBoolean(need, item, "hasMore");
                copyBoolean(need, item, "stopRecommended");
                copyNumber(need, item, "nextCursor");
                copyNode(need, item, "sourceObjectIds", 12, 160);
            }
        }
        copyNode(root, output, "issues", 6, MAX_ERROR_TEXT);
        copyNode(root, output, "visualSources", 6, 160);
        output.put("modelView", "evidence_cards");
        return write(output);
    }

    private String profileResult(String raw) {
        JsonNode root = readObject(raw);
        if (root == null) return genericResult("paper-profile", raw);
        ObjectNode output = objectMapper.createObjectNode();
        copyText(root, output, "status");
        copyBoolean(root, output, "untrustedPaperContent");
        copyNumber(root, output, "paperId");
        if (root.has("profile")) output.set("profile",
                compactNode(root.get("profile"), 0, MAX_PROFILE_ARRAY, MAX_PROFILE_TEXT));
        copyNode(root, output, "candidateSourceObjectIds", 12, 160);
        output.put("usage", "画像用于规划；论文事实仍需通过 paper-evidence 读取原文。");
        output.put("modelView", "paper_profile");
        return write(output);
    }

    private String activationResult(String skillName) {
        ObjectNode output = objectMapper.createObjectNode();
        output.put("status", "activated");
        output.put("skill", skillName == null ? "" : skillName);
        output.put("instructionsLoaded", true);
        return write(output);
    }

    private String genericResult(String toolName, String raw) {
        JsonNode root = readObject(raw);
        ObjectNode output = objectMapper.createObjectNode();
        boolean failed = root != null && (root.has("error") || Set.of(
                "error", "invalid_request", "unavailable", "stale").contains(
                root.path("status").asText("").toLowerCase()));
        String status = root == null ? "" : root.path("status").asText("").trim();
        output.put("status", status.isBlank() ? (failed ? "error" : "completed") : status);
        output.put("tool", toolName == null ? "" : toolName);
        if (root != null) {
            copyText(root, output, "responseMode");
            copyText(root, output, "groundingMode");
            copyNumber(root, output, "actionCount");
            copyNode(root, output, "citationSources", 16, 180);
            String instructions = root.path("instructions").asText("").trim();
            if (!instructions.isBlank()) output.put("instructions", bound(instructions, MAX_PROTOCOL_TEXT));
            String message = root.path("message").asText("").trim();
            if (!message.isBlank()) output.put("message", bound(message, MAX_PROTOCOL_TEXT));
        }
        if (failed) {
            String message = root == null ? raw : firstText(root, "error", "message");
            if (!message.isBlank()) output.put("message", bound(message, MAX_ERROR_TEXT));
            if (root != null) copyNode(root, output, "issues", 6, MAX_ERROR_TEXT);
        }
        return write(output);
    }

    private JsonNode compactNode(JsonNode node, int depth, int maxArray, int maxText) {
        if (node == null || node.isNull()) return objectMapper.nullNode();
        if (depth >= 5) return objectMapper.getNodeFactory().textNode("…");
        if (node.isTextual()) return objectMapper.getNodeFactory().textNode(bound(node.asText(), maxText));
        if (node.isNumber() || node.isBoolean()) return node.deepCopy();
        if (node.isArray()) {
            ArrayNode array = objectMapper.createArrayNode();
            int count = 0;
            for (JsonNode item : node) {
                if (count++ >= maxArray) break;
                array.add(compactNode(item, depth + 1, maxArray, maxText));
            }
            return array;
        }
        if (node.isObject()) {
            ObjectNode object = objectMapper.createObjectNode();
            node.fields().forEachRemaining(entry -> object.set(entry.getKey(),
                    compactNode(entry.getValue(), depth + 1, maxArray, maxText)));
            return object;
        }
        return objectMapper.getNodeFactory().textNode(bound(node.toString(), maxText));
    }

    private void copyNode(JsonNode source, ObjectNode target, String field, int maxArray, int maxText) {
        if (source.has(field)) target.set(field, compactNode(source.get(field), 0, maxArray, maxText));
    }

    private Map<String, Activation> activations(List<ChatMessage> messages) {
        Map<String, Activation> result = new LinkedHashMap<>();
        for (int index = 0; index < messages.size(); index++) {
            if (!(messages.get(index) instanceof AiMessage assistant)
                    || assistant.toolExecutionRequests() == null) continue;
            for (ToolExecutionRequest request : assistant.toolExecutionRequests()) {
                if ("activate_skill".equals(request.name())) {
                    result.put(request.id(), new Activation(index, skillName(request)));
                }
            }
        }
        return result;
    }

    private Set<String> usedActivationIds(List<ChatMessage> messages, Map<String, Activation> activations) {
        Set<String> used = new LinkedHashSet<>();
        for (Map.Entry<String, Activation> entry : activations.entrySet()) {
            int end = messages.size();
            for (Activation other : activations.values()) {
                if (other.messageIndex() > entry.getValue().messageIndex()) end = Math.min(end, other.messageIndex());
            }
            for (int index = entry.getValue().messageIndex() + 1; index < end; index++) {
                if (messages.get(index) instanceof ToolExecutionResultMessage tool
                        && isSkillTool(entry.getValue().skillName(), tool.toolName())) {
                    used.add(entry.getKey());
                    break;
                }
            }
        }
        return used;
    }

    private String skillName(ToolExecutionRequest request) {
        try { return objectMapper.readTree(request.arguments()).path("skill_name").asText("").trim(); }
        catch (Exception ignored) { return ""; }
    }

    private static boolean isSkillTool(String skillName, String toolName) {
        if ("paper-profile".equals(skillName)) return PaperOverviewToolRegistry.TOOL_NAME.equals(toolName);
        if ("paper-evidence".equals(skillName)) return isPaperReadTool(toolName);
        if ("paper-action".equals(skillName)) return "paper_action".equals(toolName);
        return false;
    }

    private static boolean isPaperReadTool(String name) {
        return "retrieve_paper_evidence".equals(name) || "read_pages".equals(name);
    }

    private static int currentTurnStart(List<ChatMessage> messages) {
        int firstTool = -1;
        for (int index = 0; index < messages.size(); index++) {
            ChatMessage message = messages.get(index);
            if (message instanceof ToolExecutionResultMessage
                    || message instanceof AiMessage assistant && assistant.hasToolExecutionRequests()) {
                firstTool = index;
                break;
            }
        }
        int from = firstTool < 0 ? messages.size() - 1 : firstTool - 1;
        for (int index = from; index >= 0; index--) if (messages.get(index) instanceof UserMessage) return index;
        return -1;
    }

    private static List<HistoryTurn> historicalTurns(List<ChatMessage> messages, int currentTurnStart) {
        List<HistoryTurn> turns = new ArrayList<>();
        for (int index = 0; index + 1 < currentTurnStart; index++) {
            if (!(messages.get(index) instanceof UserMessage)
                    || !(messages.get(index + 1) instanceof AiMessage assistant)
                    || assistant.hasToolExecutionRequests()) continue;
            turns.add(new HistoryTurn(index, index + 1));
            index++;
        }
        return turns;
    }

    private static List<ChatMessage> withoutIndexes(List<ChatMessage> messages, Set<Integer> removed) {
        List<ChatMessage> result = new ArrayList<>(messages.size() - removed.size());
        for (int index = 0; index < messages.size(); index++) if (!removed.contains(index)) result.add(messages.get(index));
        return List.copyOf(result);
    }

    static int estimatedRequestTokens(ChatRequest request) {
        if (request == null) return 0;
        int tokens = request.messages() == null ? 0
                : request.messages().stream().mapToInt(AgentRunContextHarness::estimatedMessageTokens).sum();
        if (request.toolSpecifications() != null) tokens += request.toolSpecifications().stream()
                .mapToInt(value -> estimatedTextTokens(value.toString())).sum();
        return tokens + (request.messages() == null ? 0 : request.messages().size() * 8);
    }

    private static int estimatedMessageTokens(ChatMessage message) {
        if (message instanceof ToolExecutionResultMessage tool) return estimatedTextTokens(tool.text()) + 12;
        if (message instanceof AiMessage assistant) {
            int tokens = estimatedTextTokens(assistant.text());
            if (assistant.toolExecutionRequests() != null) for (ToolExecutionRequest request : assistant.toolExecutionRequests()) {
                tokens += estimatedTextTokens(request.name()) + estimatedTextTokens(request.arguments()) + 16;
            }
            return tokens;
        }
        if (message instanceof UserMessage user && user.contents() != null) {
            int tokens = 0;
            for (Content content : user.contents()) {
                if (content instanceof TextContent text) tokens += estimatedTextTokens(text.text());
                else if (content instanceof ImageContent) tokens += 1_100;
            }
            return tokens;
        }
        return estimatedTextTokens(message.toString());
    }

    private static int estimatedTextTokens(String value) {
        if (value == null || value.isBlank()) return 0;
        int ascii = 0, nonAscii = 0;
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) <= 0x7f) ascii++; else nonAscii++;
        }
        return (int) Math.ceil(ascii / 3.5d + nonAscii);
    }

    synchronized RequestMetrics lastMetrics() { return lastMetrics; }

    synchronized RunStats recordActualPromptTokens(int promptTokens) {
        int actual = Math.max(0, promptTokens);
        runStats = new RunStats(runStats.initialPromptTokens(), runStats.cumulativeEstimatedPromptTokens(),
                runStats.cumulativePromptTokens() + actual, runStats.maxEstimatedPromptTokens(),
                Math.max(runStats.maxPromptTokens(), actual));
        if (lastMetrics != null) lastMetrics = lastMetrics.withStats(runStats);
        return runStats;
    }

    synchronized RunStats runStats() { return runStats; }
    synchronized void markRequestSent() { }

    private JsonNode readObject(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try { JsonNode node = objectMapper.readTree(raw); return node != null && node.isObject() ? node : null; }
        catch (Exception ignored) { return null; }
    }

    private String write(JsonNode node) {
        try { return objectMapper.writeValueAsString(node); }
        catch (Exception error) { throw new IllegalStateException("failed to project agent context", error); }
    }

    private static void copyText(JsonNode source, ObjectNode target, String field) {
        if (source.has(field) && !source.path(field).asText("").isBlank()) target.put(field, source.path(field).asText());
    }
    private static void copyNumber(JsonNode source, ObjectNode target, String field) {
        if (source.has(field) && source.path(field).isNumber()) target.set(field, source.path(field));
    }
    private static void copyBoolean(JsonNode source, ObjectNode target, String field) {
        if (source.has(field) && source.path(field).isBoolean()) target.put(field, source.path(field).asBoolean());
    }
    private static String firstText(JsonNode source, String... fields) {
        for (String field : fields) { String value = source.path(field).asText("").trim(); if (!value.isBlank()) return value; }
        return "";
    }
    private static String bound(String value, int max) {
        if (value == null || value.length() <= max) return value == null ? "" : value;
        return value.substring(0, Math.max(0, max - 1)) + "…";
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
    private record ProjectionPolicy(int bodyCards, int bodyCharacters) { }
    private record PreparedView(ChatRequest request, int tokens, boolean changed,
                                int changedMessages, int projectedSources) { }
    private record Activation(int messageIndex, String skillName) { }
    private record HistoryTurn(int start, int end) { }
    private record ToolExchange(int start, int end) { }
    private static final class ProjectionState {
        private final int bodyCharacters;
        private final Set<String> seenSources = new LinkedHashSet<>();
        private int remainingBodyCards;
        private boolean changed;
        private int changedMessages;
        private int projectedSources;
        private ProjectionState(int bodyCards, int bodyCharacters) {
            this.remainingBodyCards = bodyCards;
            this.bodyCharacters = bodyCharacters;
        }
    }
}
