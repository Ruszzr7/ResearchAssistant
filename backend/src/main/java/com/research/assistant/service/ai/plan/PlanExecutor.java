package com.research.assistant.service.ai.plan;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.service.ai.skill.Skill;
import com.research.assistant.service.ai.skill.SkillContext;
import com.research.assistant.service.ai.skill.SkillRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 计划执行器 —— 顺序执行 {@link Plan} 中的每一步 Skill。
 */
@Component
public class PlanExecutor {

    private static final Logger log = LoggerFactory.getLogger(PlanExecutor.class);

    private static final Pattern STEP_REF = Pattern.compile("\\{\\{step(\\d+)\\}\\}");

    private final SkillRegistry skillRegistry;
    private final ObjectMapper objectMapper;

    public PlanExecutor(@Lazy SkillRegistry skillRegistry, ObjectMapper objectMapper) {
        this.skillRegistry = skillRegistry;
        this.objectMapper = objectMapper;
    }

    /**
     * 执行计划，返回最后一步的输出。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Object execute(Plan plan, SkillContext ctx) {
        if (plan == null || plan.steps() == null || plan.steps().isEmpty()) {
            return null;
        }

        List<Object> results = new ArrayList<>();
        for (int i = 0; i < plan.steps().size(); i++) {
            PlanStep step = plan.steps().get(i);
            ctx.stage("正在执行：" + step.skill());

            Skill skill = skillRegistry.get(step.skill());
            if (skill == null) {
                throw new RuntimeException("未知 Skill: " + step.skill());
            }

            Map<String, Object> resolved = resolveArguments(step.arguments(), results);
            Object input = objectMapper.convertValue(resolved, skill.inputType());
            Object output;
            try {
                output = skill.execute(ctx, input);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException("执行 Skill [" + step.skill() + "] 失败: " + e.getMessage(), e);
            }
            results.add(output);
        }

        return results.get(results.size() - 1);
    }

    private Map<String, Object> resolveArguments(Map<String, Object> arguments, List<Object> results) {
        Map<String, Object> resolved = new HashMap<>();
        for (Map.Entry<String, Object> entry : arguments.entrySet()) {
            resolved.put(entry.getKey(), resolveValue(entry.getValue(), results));
        }
        return resolved;
    }

    private Object resolveValue(Object value, List<Object> results) {
        if (!(value instanceof String s)) {
            return value;
        }
        s = s.trim();

        // 完整占位符：直接返回上一步结果对象
        if ("{{prev}}".equals(s) && !results.isEmpty()) {
            return results.get(results.size() - 1);
        }
        Matcher m = STEP_REF.matcher(s);
        if (m.matches()) {
            int idx = Integer.parseInt(m.group(1));
            if (idx < 0 || idx >= results.size()) {
                throw new RuntimeException("计划占位符越界: {{step" + idx + "}}");
            }
            return results.get(idx);
        }

        // 字符串内嵌占位符：只做字符串替换
        String replaced = s;
        if (replaced.contains("{{prev}}") && !results.isEmpty()) {
            replaced = replaced.replace("{{prev}}", stringify(results.get(results.size() - 1)));
        }
        Matcher embed = STEP_REF.matcher(replaced);
        StringBuilder sb = new StringBuilder();
        while (embed.find()) {
            int idx = Integer.parseInt(embed.group(1));
            if (idx < 0 || idx >= results.size()) {
                throw new RuntimeException("计划占位符越界: {{step" + idx + "}}");
            }
            embed.appendReplacement(sb, Matcher.quoteReplacement(stringify(results.get(idx))));
        }
        embed.appendTail(sb);
        return sb.toString();
    }

    private String stringify(Object value) {
        if (value == null) return "";
        if (value instanceof String s) return s;
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            log.warn("序列化步骤结果失败: {}", e.getMessage());
            return value.toString();
        }
    }
}
