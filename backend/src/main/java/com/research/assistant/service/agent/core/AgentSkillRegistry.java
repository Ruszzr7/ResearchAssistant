package com.research.assistant.service.agent.core;

import com.research.assistant.service.agent.action.PaperActionType;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Exposes only capabilities that are objectively available in the current
 * context. Intent selection remains with the model; this registry does not
 * classify the user's message or prescribe a sequence of calls.
 */
@Service
public class AgentSkillRegistry {

    private static final String ACTION_SCHEMA = """
            {"type":"object","properties":{
            "actionType":{"type":"string","enum":["JUMP","HIGHLIGHT","UNDERLINE","NOTE","COMMENT"],"description":"The single page operation explicitly requested by the user."},
            "sourceObjectId":{"type":"string","minLength":1,"maxLength":160,"description":"Trusted sourceObjectId from the current selection or a paper read result; never invent one."},
            "content":{"type":"string","maxLength":2000,"description":"Required only for NOTE or COMMENT; the note or comment text requested by the user."},
            "color":{"type":"string","maxLength":16,"description":"Optional annotation color, preferably a six-digit CSS hex color such as #ffee58."}},
            "required":["actionType","sourceObjectId"],"additionalProperties":false}
            """;
    private final PaperReadToolRegistry paperReadToolRegistry;
    private final PaperOverviewToolRegistry paperOverviewToolRegistry;

    public AgentSkillRegistry(PaperReadToolRegistry paperReadToolRegistry) {
        this(paperReadToolRegistry, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AgentSkillRegistry(PaperReadToolRegistry paperReadToolRegistry,
                              PaperOverviewToolRegistry paperOverviewToolRegistry) {
        this.paperReadToolRegistry = paperReadToolRegistry;
        this.paperOverviewToolRegistry = paperOverviewToolRegistry;
    }

    public List<AgentSkill> available(AgentContextSnapshot context, String userMessage) {
        if (context == null || context.sourceCatalog() == null) return List.of();
        List<AgentSkill> skills = new ArrayList<>();
        // Availability is a capability boundary only. Whether an already prepared
        // overview is useful, stale in the current context, or worth loading again
        // is an Agent decision; a previous call must not hide the skill.
        if (paperOverviewToolRegistry != null) {
            skills.add(new AgentSkill(
                    "paper_overview",
                    "按需读取当前论文已生成的整体画像。",
                    "仅在首次建立论文全局理解、回答宏观概述，或当前上下文已无法支撑全局判断时使用。画像是理解用的参考，不是局部原文证据；若画像已经在当前上下文、问题只需要局部事实或页面操作，不要调用。上下文被压缩且确实缺少全局信息时可以再次调用。",
                    List.of(paperOverviewToolRegistry.definition())));
        }
        skills.add(new AgentSkill(
                "paper_evidence",
                "按需批量查阅当前论文的原文、公式、定理、假设和实验结果。",
                "当需要核实论文事实、公式、实验结果、准确页码或可点击引用时使用。一次请求应把当前已知的多个证据需求合并提交；结果会标明各需求是否找到。只有结果部分覆盖、未找到或需求范围变化时才再次调用；完全相同的请求通常不会产生新信息。CAPTION 只作辅助，性能结论优先采用正文、表格或公式。不得伪造引用。",
                paperReadToolRegistry.definitions()));
        AgentSkill actions = new AgentSkill(
                "page_action",
                "按用户明确要求对当前论文页面执行一次定位或修改操作。",
                "只有用户明确要求跳转、高亮、下划线、添加笔记或批注时才使用。可点击或可跳转的回答引用应通过 submit_answer 附上证据，不要调用此工具。一次调用只提交一个动作；动作目标必须来自当前选区或可信论文上下文中的 sourceObjectId，目标不明确时先追问，不得猜测或生成坐标。",
                List.of(new AgentToolDefinition("paper_action",
                        "Issue one validated paper page operation. The client performs it after receiving the action ticket; this tool does not search paper content.", ACTION_SCHEMA)));
        skills.add(actions);
        return List.copyOf(skills);
    }

    public List<AgentToolDefinition> tools(AgentContextSnapshot context, String userMessage) {
        Set<String> names = new LinkedHashSet<>();
        List<AgentToolDefinition> result = new ArrayList<>();
        for (AgentSkill skill : available(context, userMessage)) {
            for (AgentToolDefinition tool : skill.tools()) {
                if (names.add(tool.name())) result.add(tool);
            }
        }
        return List.copyOf(result);
    }

    public String prompt(AgentContextSnapshot context, String userMessage) {
        List<AgentSkill> skills = available(context, userMessage);
        if (skills.isEmpty()) return "";
        StringBuilder prompt = new StringBuilder("Optional capabilities available in this turn:\n");
        for (AgentSkill skill : skills) {
            prompt.append("- ").append(skill.id()).append(": ")
                    .append(skill.description()).append('\n')
                    .append("  Guidance: ").append(skill.instructions()).append('\n');
        }
        return prompt.toString().trim();
    }

    public static boolean isMutationTool(String name) {
        return "paper_action".equals(name);
    }

    public static boolean isPaperReadTool(String name) {
        return PaperOverviewToolRegistry.TOOL_NAME.equals(name)
                || "retrieve_paper_evidence".equals(name) || "read_pages".equals(name);
    }

    public static PaperActionType actionType(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("actionType is required");
        try {
            return PaperActionType.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("unsupported paper action: " + value);
        }
    }
}
