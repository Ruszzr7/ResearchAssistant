package com.research.assistant.service.ai.plan;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.common.JsonUtils;
import com.research.assistant.service.LLMService;
import com.research.assistant.service.ai.skill.Skill;
import com.research.assistant.service.ai.skill.SkillRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * 任务规划器 —— 根据所有 Skill 的描述，让 LLM 生成执行计划。
 */
@Component
public class Planner {

    private static final Logger log = LoggerFactory.getLogger(Planner.class);

    private static final String SYSTEM_PROMPT = """
你是一位 AI 编排助手。你的任务是根据用户目标，从下面列出的 Skill 中选择一个或多个，生成执行计划。

**可用 Skill**：
%s

**输出要求**：
- 只返回 JSON，不要任何额外解释。
- JSON 格式：{"steps": [{"skill": "skill-name", "arguments": {...}}]}
- arguments 必须与该 Skill 的输入字段完全一致。
- 如果多步骤之间需要传递结果，使用占位符：
  - {{prev}} 表示上一步的输出
  - {{step0}}、{{step1}} 表示指定步骤的输出（从 0 开始）
- 如果用户目标无法匹配任何 Skill，返回 {"steps": []}。

**示例**：
用户目标："对比论文 28 和 25"
输出：{"steps": [{"skill": "compare-papers", "arguments": {"paperIds": [28, 25], "customDimensions": ""}}]}

用户目标："分析论文 28、25、24 的研究空白并验证"
输出：{"steps": [{"skill": "analyze-gaps", "arguments": {"paperIds": [28, 25, 24], "folderId": null}}, {"skill": "verify-gaps", "arguments": {"gapReport": "{{prev}}"}}]}
""";

    private final LLMService llmService;
    private final SkillRegistry skillRegistry;
    private final ObjectMapper objectMapper;

    public Planner(LLMService llmService, @Lazy SkillRegistry skillRegistry, ObjectMapper objectMapper) {
        this.llmService = llmService;
        this.skillRegistry = skillRegistry;
        this.objectMapper = objectMapper;
    }

    /**
     * 根据用户目标生成执行计划。
     */
    public Plan plan(String goal) {
        String skillDescriptions = buildSkillDescriptions();
        String prompt = String.format(SYSTEM_PROMPT, skillDescriptions) + "\n\n用户目标：" + goal;

        try {
            String response = llmService.chat("你是一位 AI 编排助手，只返回 JSON 格式的执行计划。", prompt);
            String json = JsonUtils.extractJson(response);
            Plan plan = objectMapper.readValue(json, Plan.class);
            if (plan == null || plan.steps() == null) {
                return emptyPlan();
            }
            return plan;
        } catch (Exception e) {
            log.warn("Planner 生成计划失败: {}", e.getMessage());
            return emptyPlan();
        }
    }

    private String buildSkillDescriptions() {
        StringBuilder sb = new StringBuilder();
        for (Skill<?, ?> skill : skillRegistry.all()) {
            sb.append("- ").append(skill.name()).append(": ").append(skill.description()).append("\n");
        }
        return sb.toString();
    }

    private Plan emptyPlan() {
        return new Plan(java.util.List.of());
    }
}
