package com.research.assistant.service.agent.runtime;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Locale;

/** Stable failure codes and user-facing messages for background Agent runs. */
public final class AgentRunFailureClassifier {

    public static final String PROVIDER_TRANSIENT = "PROVIDER_TRANSIENT";
    public static final String PROJECT_SCHEDULER = "PROJECT_SCHEDULER";
    public static final String PROJECT_LIMIT = "PROJECT_LIMIT";
    public static final String MODEL_PROTOCOL = "MODEL_PROTOCOL";
    public static final String PROJECT_INTERNAL = "PROJECT_INTERNAL";
    public static final String USER_ACTION = "USER_ACTION";
    public static final String UNKNOWN = "UNKNOWN";

    private AgentRunFailureClassifier() {
    }

    public static Failure classify(Throwable error) {
        String type = error == null ? "" : error.getClass().getSimpleName().toLowerCase(Locale.ROOT);
        String detail = error == null || error.getMessage() == null
                ? "" : error.getMessage().toLowerCase(Locale.ROOT);
        String combined = type + " " + detail;
        if (combined.contains("timeout") || combined.contains("timed out")) {
            return new Failure("MODEL_TIMEOUT", "模型响应超时，请稍后重试");
        }
        if (combined.contains("overloaded") || combined.contains("rate limit")
                || combined.contains("rate_limit") || combined.contains("429")) {
            return new Failure("MODEL_OVERLOADED", "模型服务当前繁忙，请稍后重试");
        }
        if (combined.contains("context_budget_exceeded")) {
            return new Failure("CONTEXT_BUDGET_EXCEEDED", "当前上下文内容过长，请缩小输入范围");
        }
        if (combined.contains("model_call_limit_exceeded")
                || combined.contains("tool_call_limit_exceeded")) {
            return new Failure("AGENT_CALL_LIMIT", "论文助手调用次数已达上限，请缩小问题范围后重试");
        }
        if (combined.contains("max tool") || combined.contains("tool calling round")) {
            return new Failure("TOOL_ROUND_LIMIT", "模型工具调用进入异常循环，请缩小问题范围后重试");
        }
        if (combined.contains("payload limit") || combined.contains("result exceeded")) {
            return new Failure("TOOL_OUTPUT_OVERSIZE", "论文读取结果过大，请缩小页码或问题范围");
        }
        if (combined.contains("answer_submission_required")) {
            return new Failure("ANSWER_SUBMISSION_REQUIRED", "模型未通过结构化答案提交，请重试");
        }
        if (combined.contains("grounding_submission_required")) {
            return new Failure("EVIDENCE_SUBMISSION_REQUIRED", "论文证据已读取，但回答未完成证据绑定，请重试");
        }
        if (combined.contains("citation") || combined.contains("grounded source")
                || combined.contains("source was not read")) {
            return new Failure("EVIDENCE_VALIDATION_FAILED", "回答证据校验未通过，请重试");
        }
        if (combined.contains("capability_not_verified") || combined.contains("did_not_call_tool")) {
            return new Failure("MODEL_TOOL_CALLING_UNAVAILABLE", "当前对话模型未通过Agent工具调用测试");
        }
        if (combined.contains("empty response") || combined.contains("empty direct answer")
                || combined.contains("空响应")) {
            return new Failure("MODEL_EMPTY_RESPONSE", "模型未返回有效内容，请重试");
        }
        if (combined.contains("connect") || combined.contains("network") || combined.contains("socket")) {
            return new Failure("MODEL_CONNECTION_FAILED", "模型服务连接失败，请检查API设置或稍后重试");
        }
        return new Failure("AGENT_INTERNAL_ERROR", "论文助手执行失败，请稍后重试");
    }

    /**
     * Rebuilds the stable classification for a persisted run without exposing
     * the original provider or prompt text.
     */
    public static Failure fromCode(String code) {
        String normalized = code == null || code.isBlank() ? "AGENT_INTERNAL_ERROR" : code.trim();
        return new Failure(normalized, userMessage(normalized), categoryFor(normalized), retryableFor(normalized));
    }

    public static String categoryFor(String code) {
        if (code == null) return UNKNOWN;
        return switch (code) {
            case "MODEL_TIMEOUT", "RUN_TIMEOUT", "MODEL_OVERLOADED", "MODEL_CONNECTION_FAILED",
                    "MODEL_EMPTY_RESPONSE" -> PROVIDER_TRANSIENT;
            case "QUEUE_TIMEOUT" -> PROJECT_SCHEDULER;
            case "CONTEXT_BUDGET_EXCEEDED", "AGENT_CALL_LIMIT", "TOOL_ROUND_LIMIT",
                    "TOOL_OUTPUT_OVERSIZE" -> PROJECT_LIMIT;
            case "ANSWER_SUBMISSION_REQUIRED", "EVIDENCE_SUBMISSION_REQUIRED", "EVIDENCE_VALIDATION_FAILED",
                    "MODEL_TOOL_CALLING_UNAVAILABLE", "AGENT_PROTOCOL_ERROR" -> MODEL_PROTOCOL;
            case "USER_CANCELLED" -> USER_ACTION;
            case "AGENT_DISPATCH_FAILED", "AGENT_INTERNAL_ERROR" -> PROJECT_INTERNAL;
            default -> UNKNOWN;
        };
    }

    /** True only when a single bounded retry is a reasonable default. */
    public static boolean retryableFor(String code) {
        return switch (categoryFor(code)) {
            case PROVIDER_TRANSIENT, PROJECT_SCHEDULER -> true;
            default -> false;
        };
    }

    /**
     * Resolves a watchdog timeout when the worker's exception raced the timeout
     * scanner. A NOT_SENT trace proves the provider was not called.
     */
    public static Failure classifyTimeoutTrace(JsonNode trace, Integer maxModelCalls) {
        if (trace != null && trace.isArray()) {
            for (int index = trace.size() - 1; index >= 0; index--) {
                JsonNode call = trace.get(index);
                if (!"FAILED".equals(call.path("status").asText())) continue;
                if (!"NOT_SENT".equals(call.path("responseKind").asText())) break;
                int ordinal = call.path("ordinal").asInt(0);
                int maxCalls = maxModelCalls == null ? 0 : maxModelCalls;
                if (maxCalls > 0 && ordinal > maxCalls) {
                    return fromCode("AGENT_CALL_LIMIT");
                }
                if (call.path("estimatedPromptTokens").asInt(0) > 16_000) {
                    return fromCode("CONTEXT_BUDGET_EXCEEDED");
                }
                break;
            }
        }
        return fromCode("RUN_TIMEOUT");
    }

    public static String userMessage(String code) {
        if (code == null) return "论文助手执行失败，请稍后重试";
        return switch (code) {
            case "USER_CANCELLED" -> "已取消回答";
            case "QUEUE_TIMEOUT" -> "论文助手排队时间过长，请稍后重试";
            case "RUN_TIMEOUT", "MODEL_TIMEOUT" -> "模型响应超时，请稍后重试";
            case "MODEL_OVERLOADED" -> "模型服务当前繁忙，请稍后重试";
            case "CONTEXT_BUDGET_EXCEEDED" -> "当前上下文内容过长，请缩小输入范围";
            case "AGENT_CALL_LIMIT" -> "论文助手调用次数已达上限，请缩小问题范围后重试";
            case "TOOL_ROUND_LIMIT" -> "模型工具调用进入异常循环，请缩小问题范围后重试";
            case "TOOL_OUTPUT_OVERSIZE" -> "论文读取结果过大，请缩小页码或问题范围";
            case "ANSWER_SUBMISSION_REQUIRED" -> "模型未通过结构化答案提交，请重试";
            case "EVIDENCE_SUBMISSION_REQUIRED" -> "论文证据已读取，但回答未完成证据绑定，请重试";
            case "EVIDENCE_VALIDATION_FAILED" -> "回答证据校验未通过，请重试";
            case "MODEL_TOOL_CALLING_UNAVAILABLE" -> "当前对话模型未通过Agent工具调用测试";
            case "MODEL_EMPTY_RESPONSE" -> "模型未返回有效内容，请重试";
            // Retained only so historical failed runs still have a readable message.
            case "AGENT_PROTOCOL_ERROR" -> "模型返回格式不符合要求，请重试";
            case "MODEL_CONNECTION_FAILED" -> "模型服务连接失败，请检查API设置或稍后重试";
            default -> "论文助手执行失败，请稍后重试";
        };
    }

    public record Failure(String code, String userMessage, String category, boolean retryable) {
        public Failure(String code, String userMessage) {
            this(code, userMessage, categoryFor(code), retryableFor(code));
        }
    }
}
