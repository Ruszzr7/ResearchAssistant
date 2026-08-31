package com.research.assistant.service.agent.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.service.ai.LangChain4jModelFactory;
import dev.langchain4j.agent.tool.ReturnBehavior;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.TokenWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.openai.OpenAiTokenCountEstimator;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.tool.AiServiceTool;
import dev.langchain4j.service.tool.ToolErrorHandlerResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thin LangChain4j adapter. It deliberately contains no paper policy, evidence
 * validation, persistence, or UI action logic.
 */
@Component
public class LangChain4jPaperAgentExecutor implements PaperAgentFrameworkExecutor {

    // Do not impose a product-level tool-round budget here. LangChain4j keeps its
    // own broad emergency safeguard; the Agent decides whether another capability
    // call is useful after seeing the previous result.
    static final int MEMORY_TOKEN_LIMIT = 110_000;

    private final LangChain4jModelFactory modelFactory;
    private final ObjectMapper objectMapper;

    public LangChain4jPaperAgentExecutor(LangChain4jModelFactory modelFactory, ObjectMapper objectMapper) {
        this.modelFactory = modelFactory;
        this.objectMapper = objectMapper;
    }

    @Override
    public AgentFrameworkResult execute(List<AgentChatEntry> messages,
                                        List<AgentToolDefinition> tools,
                                        ToolHandler handler) {
        return execute(modelFactory.createAgentChatModel(), messages, tools, handler, trace -> { });
    }

    @Override
    public AgentFrameworkResult execute(List<AgentChatEntry> messages,
                                        List<AgentToolDefinition> tools,
                                        ToolHandler handler,
                                        ModelCallObserver observer) {
        return execute(modelFactory.createAgentChatModel(), messages, tools, handler, observer);
    }

    AgentFrameworkResult execute(ChatModel model,
                                 List<AgentChatEntry> messages,
                                 List<AgentToolDefinition> tools,
                                 ToolHandler handler) {
        return execute(model, messages, tools, handler, trace -> { });
    }

    AgentFrameworkResult execute(ChatModel model,
                                 List<AgentChatEntry> messages,
                                 List<AgentToolDefinition> tools,
                                 ToolHandler handler,
                                 ModelCallObserver observer) {
        if (messages == null || messages.isEmpty()) throw new IllegalArgumentException("agent messages are required");
        if (tools == null || tools.isEmpty()) throw new IllegalArgumentException("agent tools are required");

        List<AgentChatEntry> input = combineSystemMessages(messages);
        AgentChatEntry current = input.get(input.size() - 1);
        if (current.role() != AgentChatEntry.Role.USER) {
            throw new IllegalArgumentException("the final agent message must be the current user message");
        }

        ChatMemory memory = TokenWindowChatMemory.builder()
                .id("paper-agent-invocation")
                .maxTokens(MEMORY_TOKEN_LIMIT, new OpenAiTokenCountEstimator("gpt-4o"))
                .alwaysKeepSystemMessageFirst(true)
                .build();
        for (int index = 0; index < input.size() - 1; index++) {
            memory.add(toMessage(input.get(index)));
        }

        List<AiServiceTool> serviceTools = tools.stream()
                .map(definition -> toTool(definition, handler))
                .toList();
        PaperAssistantAiService assistant = assistant(observed(model, observer), memory, serviceTools);

        Result<String> result = assistant.chat(current.content());
        TokenUsage usage = result.tokenUsage();
        int resultModelCalls = result.intermediateResponses() == null ? 1 : result.intermediateResponses().size() + 1;
        int modelCalls = resultModelCalls;
        int resultToolCalls = result.toolExecutions() == null ? 0 : result.toolExecutions().size();
        int toolCalls = resultToolCalls;
        String content = result.content();
        if ((content == null || content.isBlank()) && result.toolExecutions() != null
                && !result.toolExecutions().isEmpty()) {
            var last = result.toolExecutions().get(result.toolExecutions().size() - 1);
            if (isTerminal(last.request().name())) content = last.result();
        }
        return new AgentFrameworkResult(content, modelCalls, toolCalls,
                usage == null || usage.inputTokenCount() == null ? 0 : usage.inputTokenCount(),
                usage == null || usage.outputTokenCount() == null ? 0 : usage.outputTokenCount());
    }

    private static ChatModel observed(ChatModel delegate, ModelCallObserver observer) {
        AtomicInteger ordinal = new AtomicInteger();
        return new ChatModel() {
            @Override
            public dev.langchain4j.model.chat.response.ChatResponse doChat(
                    dev.langchain4j.model.chat.request.ChatRequest request) {
                int call = ordinal.incrementAndGet();
                long started = System.nanoTime();
                try {
                    var response = delegate.chat(request);
                    TokenUsage usage = response.tokenUsage();
                    notifyObserver(observer, new AgentModelCallTrace(call, "COMPLETED", elapsedMs(started),
                            request.messages().size(), request.toolSpecifications() == null
                            ? 0 : request.toolSpecifications().size(),
                            token(usage == null ? null : usage.inputTokenCount()),
                            token(usage == null ? null : usage.outputTokenCount()),
                            response.finishReason() == null ? null : response.finishReason().name(), null));
                    return response;
                } catch (RuntimeException error) {
                    notifyObserver(observer, new AgentModelCallTrace(call, "FAILED", elapsedMs(started),
                            request.messages().size(), request.toolSpecifications() == null
                            ? 0 : request.toolSpecifications().size(), 0, 0, null,
                            error.getClass().getSimpleName()));
                    throw error;
                }
            }
        };
    }

    private static void notifyObserver(ModelCallObserver observer, AgentModelCallTrace trace) {
        try {
            observer.completed(trace);
        } catch (RuntimeException ignored) {
            // Telemetry must never turn a successful provider response into a failed Agent run.
        }
    }

    private static long elapsedMs(long started) {
        return Math.max(0, (System.nanoTime() - started) / 1_000_000);
    }

    private static int token(Integer value) {
        return value == null ? 0 : value;
    }

    private PaperAssistantAiService assistant(ChatModel model, ChatMemory memory,
                                               List<AiServiceTool> serviceTools) {
        var builder = AiServices.builder(PaperAssistantAiService.class)
                .chatModel(model)
                .chatMemory(memory)
                .toolArgumentsErrorHandler((error, context) -> toolError(error))
                .toolExecutionErrorHandler((error, context) -> toolError(error))
                .chatRequestTransformer(request -> request.toBuilder()
                        .parameters(request.parameters().overrideWith(ChatRequestParameters.builder()
                                // The model owns intent selection. In particular, Kimi's
                                // thinking/tool protocol rejects forced tool_choice=required;
                                // AUTO still lets it call a paper skill whenever the prompt
                                // requires one and keeps ordinary turns direct.
                                .toolChoice(ToolChoice.AUTO).build()))
                        .build());
        if (serviceTools != null && !serviceTools.isEmpty()) builder.tools(serviceTools);
        return builder.build();
    }

    private AiServiceTool toTool(AgentToolDefinition definition, ToolHandler handler) {
        ToolSpecification specification = toSpecification(definition);
        ReturnBehavior behavior = isTerminal(definition.name())
                ? ReturnBehavior.IMMEDIATE_IF_LAST : ReturnBehavior.TO_LLM;
        return AiServiceTool.builder()
                .toolSpecification(specification)
                .toolExecutor((request, memoryId) -> handler.execute(new AgentToolRequest(
                        stableId(request), request.name(), request.arguments())))
                .returnBehavior(behavior)
                .build();
    }

    private ToolSpecification toSpecification(AgentToolDefinition definition) {
        try {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("name", definition.name());
            json.put("description", definition.description());
            json.put("parameters", objectMapper.readValue(definition.parametersJsonSchema(), Map.class));
            return ToolSpecification.fromJson(objectMapper.writeValueAsString(json));
        } catch (Exception error) {
            throw new IllegalArgumentException("invalid tool schema: " + definition.name(), error);
        }
    }

    private static ChatMessage toMessage(AgentChatEntry entry) {
        return switch (entry.role()) {
            case SYSTEM -> SystemMessage.from(entry.content());
            case USER -> UserMessage.from(entry.content());
            case ASSISTANT -> AiMessage.from(entry.content());
            case TOOL, ASSISTANT_TOOL -> throw new IllegalArgumentException(
                    "persisted tool transcripts must not be injected into a fresh agent invocation");
        };
    }

    /** LangChain4j chat memory keeps one system message; preserve all system context explicitly. */
    static List<AgentChatEntry> combineSystemMessages(List<AgentChatEntry> messages) {
        List<String> systemParts = new ArrayList<>();
        List<AgentChatEntry> nonSystem = new ArrayList<>();
        for (AgentChatEntry message : messages) {
            if (message.role() == AgentChatEntry.Role.SYSTEM) {
                if (message.content() != null && !message.content().isBlank()) {
                    systemParts.add(message.content().trim());
                }
            } else {
                nonSystem.add(message);
            }
        }
        if (systemParts.isEmpty()) return List.copyOf(nonSystem);
        List<AgentChatEntry> result = new ArrayList<>(nonSystem.size() + 1);
        result.add(AgentChatEntry.system(String.join("\n\n", systemParts)));
        result.addAll(nonSystem);
        return List.copyOf(result);
    }

    private static boolean isTerminal(String name) {
        return "submit_answer".equals(name) || "ask_clarification".equals(name)
                || "paper_action".equals(name);
    }

    private static String stableId(ToolExecutionRequest request) {
        if (request.id() != null && !request.id().isBlank()) return request.id();
        return request.name() + "-" + Integer.toHexString(request.arguments().hashCode());
    }

    private static ToolErrorHandlerResult toolError(Throwable error) {
        String message = error == null || error.getMessage() == null
                ? "tool execution failed" : error.getMessage();
        if (message.length() > 500) message = message.substring(0, 500);
        String escaped = message.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", " ").replace("\n", " ");
        return ToolErrorHandlerResult.text("{\"error\":\"" + escaped
                + "\",\"retryable\":" + isRetryable(error) + "}");
    }

    static boolean isRetryable(Throwable error) {
        if (error == null) return false;
        String value = (error.getClass().getSimpleName() + " "
                + (error.getMessage() == null ? "" : error.getMessage())).toLowerCase(java.util.Locale.ROOT);
        return value.contains("timeout") || value.contains("timed out")
                || value.contains("rate limit") || value.contains("rate_limit")
                || value.contains("429") || value.contains("overloaded")
                || value.contains("connect") || value.contains("network")
                || value.contains("socket");
    }
}
