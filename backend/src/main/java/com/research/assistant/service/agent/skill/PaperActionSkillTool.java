package com.research.assistant.service.agent.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.service.agent.action.ActionTarget;
import com.research.assistant.service.agent.action.PaperActionResolver;
import com.research.assistant.service.agent.action.PaperActionType;
import com.research.assistant.service.agent.core.AgentToolDefinition;
import com.research.assistant.service.agent.source.PaperSourceCatalog;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Model-facing adapter for the paper-action Skill; ticket lifecycle remains runtime-owned. */
@Service
public class PaperActionSkillTool {

    public static final String SKILL_NAME = "paper-action";
    public static final String TOOL_NAME = "paper_action";
    private static final String ACTION_SCHEMA = """
            {"type":"object","properties":{
            "actionType":{"type":"string","enum":["JUMP","HIGHLIGHT","UNDERLINE","NOTE","COMMENT"],"description":"用户明确要求执行的一次页面操作。"},
            "sourceObjectId":{"type":"string","minLength":1,"maxLength":160,"description":"当前选区或论文读取结果中的可信 sourceObjectId；绝不要编造。"},
            "content":{"type":"string","maxLength":2000,"description":"仅 NOTE 或 COMMENT 需要；填写用户要求保存的笔记或评论内容。"},
            "color":{"type":"string","maxLength":16,"description":"可选的标注颜色，建议使用六位 CSS 十六进制颜色，例如 #ffee58。"}},
            "required":["actionType","sourceObjectId"],"additionalProperties":false}
            """;

    private final PaperActionResolver actionResolver;

    public PaperActionSkillTool(PaperActionResolver actionResolver) {
        this.actionResolver = actionResolver;
    }

    public List<AgentToolDefinition> definitions() {
        return List.of(new AgentToolDefinition(TOOL_NAME,
                "在加载适用的 paper-action Skill 后，执行一次经过校验的页面操作。", ACTION_SCHEMA));
    }

    public boolean supports(String toolName) {
        return TOOL_NAME.equals(toolName);
    }

    public PreparedAction prepare(PaperSourceCatalog catalog, Set<String> readableSourceIds,
                                  String argumentsJson, ObjectMapper objectMapper) {
        if (catalog == null) throw new IllegalArgumentException("论文来源尚未就绪");
        if (actionResolver == null) throw new IllegalStateException("页面操作能力不可用");
        try {
            JsonNode args = objectMapper.readTree(argumentsJson);
            PaperActionType type = actionType(requiredText(args, "actionType"));
            String sourceId = requiredText(args, "sourceObjectId");
            if (!readableSourceIds.contains(sourceId)) {
                throw new IllegalArgumentException("操作目标来源尚未读取或未在当前选区中：" + sourceId);
            }
            return prepareTrusted(catalog, type, sourceId, optionalText(args.path("content").asText(null)),
                    optionalText(args.path("color").asText(null)));
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException("论文操作参数无效", error);
        }
    }

    /** Validates an already trusted client-side action without imposing an Agent read requirement. */
    public PreparedAction prepareExplicit(PaperSourceCatalog catalog, String typeName, String sourceObjectId,
                                          String content, String color) {
        return prepareTrusted(catalog, actionType(typeName), sourceObjectId, content, color);
    }

    private PreparedAction prepareTrusted(PaperSourceCatalog catalog, PaperActionType type, String sourceId,
                                          String content, String color) {
        if (catalog == null) throw new IllegalArgumentException("论文来源尚未就绪");
        if (actionResolver == null) throw new IllegalStateException("页面操作能力不可用");
        if (sourceId == null || sourceId.isBlank()) {
            throw new IllegalArgumentException("显式页面操作需要可信的 sourceObjectId");
        }
        if ((type == PaperActionType.NOTE || type == PaperActionType.COMMENT) && content == null) {
            throw new IllegalArgumentException("NOTE 或 COMMENT 操作需要填写内容");
        }
        return new PreparedAction(type, actionResolver.resolve(catalog, sourceId), content, color);
    }

    private static PaperActionType actionType(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("actionType 参数不能为空");
        try {
            return PaperActionType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("不支持的论文页面操作：" + value);
        }
    }

    private static String requiredText(JsonNode args, String name) {
        String value = optionalText(args.path(name).asText(null));
        if (value == null) throw new IllegalArgumentException("缺少参数：" + name);
        return value;
    }

    private static String optionalText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record PreparedAction(PaperActionType type, ActionTarget target, String content, String color) { }
}
