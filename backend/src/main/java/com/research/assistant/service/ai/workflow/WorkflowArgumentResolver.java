package com.research.assistant.service.ai.workflow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 工作流参数解析器 —— 把步骤定义中的占位符替换为实际值。
 *
 * <p>支持的占位符：</p>
 * <ul>
 *   <li>{@code {{context.xxx}}} — 启动上下文中的字段</li>
 *   <li>{@code {{input.xxx}}} — 用户确认时传入的字段</li>
 *   <li>{@code {{prev}}} — 上一步输出</li>
 *   <li>{@code {{stepN}}} — 第 N 步输出（从 0 开始）</li>
 * </ul>
 */
@Component
public class WorkflowArgumentResolver {

    private static final Logger log = LoggerFactory.getLogger(WorkflowArgumentResolver.class);

    private static final Pattern STEP_REF = Pattern.compile("\\{\\{step(\\d+)\\}\\}");
    private static final Pattern CONTEXT_REF = Pattern.compile("\\{\\{context\\.([\\w.]+)\\}\\}");
    private static final Pattern INPUT_REF = Pattern.compile("\\{\\{input\\.([\\w.]+)\\}\\}");

    private final ObjectMapper objectMapper;

    public WorkflowArgumentResolver(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 解析参数，返回可用于 {@code objectMapper.convertValue} 的 Map。
     */
    public Map<String, Object> resolve(Map<String, Object> arguments,
                                         Map<String, Object> context,
                                         List<Object> results) {
        return resolve(arguments, context, Map.of(), results);
    }

    /**
     * 解析参数，支持用户确认输入。
     */
    public Map<String, Object> resolve(Map<String, Object> arguments,
                                         Map<String, Object> context,
                                         Map<String, Object> userInput,
                                         List<Object> results) {
        Map<String, Object> resolved = new HashMap<>();
        for (Map.Entry<String, Object> entry : arguments.entrySet()) {
            resolved.put(entry.getKey(), resolveValue(entry.getValue(), context, userInput, results));
        }
        return resolved;
    }

    private Object resolveValue(Object value, Map<String, Object> context,
                                Map<String, Object> userInput, List<Object> results) {
        if (!(value instanceof String s)) {
            return value;
        }
        s = s.trim();

        // 完整占位符：直接返回对象
        if ("{{prev}}".equals(s) && !results.isEmpty()) {
            return results.get(results.size() - 1);
        }
        Matcher stepMatcher = STEP_REF.matcher(s);
        if (stepMatcher.matches()) {
            return getResult(results, Integer.parseInt(stepMatcher.group(1)));
        }
        Matcher ctxMatcher = CONTEXT_REF.matcher(s);
        if (ctxMatcher.matches()) {
            return resolvePath(context, ctxMatcher.group(1), "{{context.", "}}");
        }
        Matcher inputMatcher = INPUT_REF.matcher(s);
        if (inputMatcher.matches()) {
            return resolvePath(userInput, inputMatcher.group(1), "{{input.", "}}");
        }

        // 字符串内嵌占位符：只做字符串替换
        String replaced = s;
        if (replaced.contains("{{prev}}") && !results.isEmpty()) {
            replaced = replaced.replace("{{prev}}", stringify(results.get(results.size() - 1)));
        }
        replaced = replaceRefs(replaced, STEP_REF, path -> getResult(results, Integer.parseInt(path)));
        replaced = replaceRefs(replaced, CONTEXT_REF, path -> resolvePath(context, path, "{{context.", "}}"));
        replaced = replaceRefs(replaced, INPUT_REF, path -> resolvePath(userInput, path, "{{input.", "}}"));
        return replaced;
    }

    private Object getResult(List<Object> results, int idx) {
        if (idx < 0 || idx >= results.size()) {
            throw new WorkflowException("步骤占位符越界: {{step" + idx + "}}");
        }
        return results.get(idx);
    }

    /**
     * 在 Map 树中按点分路径取值。
     */
    private Object resolvePath(Map<String, Object> root, String path,
                               String placeholderPrefix, String placeholderSuffix) {
        if (root == null) {
            throw new WorkflowException("占位符未找到: " + placeholderPrefix + path + placeholderSuffix);
        }
        Object value = root;
        for (String key : path.split("\\.")) {
            if (!(value instanceof Map<?, ?> map)) {
                throw new WorkflowException("路径无效: " + placeholderPrefix + path + placeholderSuffix);
            }
            value = map.get(key);
            if (value == null && !map.containsKey(key)) {
                throw new WorkflowException("占位符未找到: " + placeholderPrefix + path + placeholderSuffix);
            }
        }
        return value;
    }

    private String replaceRefs(String s, Pattern pattern, Function<String, Object> resolver) {
        Matcher m = pattern.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(stringify(resolver.apply(m.group(1)))));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String stringify(Object value) {
        if (value == null) return "";
        if (value instanceof String s) return s;
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            log.warn("序列化占位符值失败: {}", e.getMessage());
            return value.toString();
        }
    }
}
