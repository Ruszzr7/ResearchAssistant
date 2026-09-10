package com.research.assistant.service.agent.runtime;

import java.util.Locale;

/** Stable failure codes and user-facing messages for background Agent runs. */
public final class AgentRunFailureClassifier {

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
        if (combined.contains("empty response") || combined.contains("empty direct answer")) {
            return new Failure("MODEL_EMPTY_RESPONSE", "模型未返回有效内容，请重试");
        }
        if (combined.contains("connect") || combined.contains("network") || combined.contains("socket")) {
            return new Failure("MODEL_CONNECTION_FAILED", "模型服务连接失败，请检查API设置或稍后重试");
        }
        return new Failure("AGENT_INTERNAL_ERROR", "论文助手执行失败，请稍后重试");
    }

    public static String userMessage(String code) {
        if (code == null) return "论文助手执行失败，请稍后重试";
        return switch (code) {
            case "QUEUE_TIMEOUT" -> "论文助手排队时间过长，请稍后重试";
            case "RUN_TIMEOUT", "MODEL_TIMEOUT" -> "模型响应超时，请稍后重试";
            case "MODEL_OVERLOADED" -> "模型服务当前繁忙，请稍后重试";
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

    public record Failure(String code, String userMessage) {
    }
}
