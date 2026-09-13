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
    private static final int MAX_BATCH_TARGETS = 8;
    private static final String OPERATION_SCHEMA = """
            {"type":"object","description":"仅用于问题加页面操作的复合请求。结构与 submit_answer 相同，回答和操作目标必须由同一批已读来源支持；纯操作请求不要填写。","properties":{
            "actionType":{"type":"string","enum":["JUMP","HIGHLIGHT","UNDERLINE","NOTE","COMMENT"],"description":"对一个或多个明确来源执行的页面操作类型。"},
            "sourceObjectId":{"type":"string","minLength":1,"maxLength":160,"description":"当前选区或论文读取结果中的可信来源 ID。"},
            "sourceObjectIds":{"type":"array","minItems":1,"maxItems":8,"uniqueItems":true,"items":{"type":"string","minLength":1,"maxLength":160},"description":"同一种操作作用于多个明确来源时填写。"},
            "content":{"type":"string","maxLength":2000,"description":"仅 NOTE 或 COMMENT 需要。"},
            "color":{"type":"string","maxLength":16,"description":"可选的六位 CSS 十六进制颜色，例如 #ffee58。"}},
            "required":["actionType"],"additionalProperties":false}
            """;
    private static final String ANSWER_SCHEMA = """
            {"type":"object","properties":{
            "groundingMode":{"type":"string","enum":["PAPER","GENERAL_KNOWLEDGE","MIXED"]},
            "answerBlocks":{"type":"array","minItems":1,"items":{"type":"object","properties":{
            "text":{"type":"string","minLength":1},
            "sourceObjectIds":{"type":"array","items":{"type":"string"}}},
            "required":["text","sourceObjectIds"],"additionalProperties":false}}},
            "required":["groundingMode","answerBlocks"],"additionalProperties":false}
            """;
    private static final String ACTION_SCHEMA = """
            {"type":"object","properties":{
            "actionType":{"type":"string","enum":["JUMP","HIGHLIGHT","UNDERLINE","NOTE","COMMENT"],"description":"兼容单项请求；包含多个不同操作时请改用 operations。"},
            "sourceObjectId":{"type":"string","minLength":1,"maxLength":160,"description":"当前选区或论文读取结果中的可信 sourceObjectId；单个目标时使用，绝不要编造。"},
            "sourceObjectIds":{"type":"array","minItems":1,"maxItems":8,"uniqueItems":true,"items":{"type":"string","minLength":1,"maxLength":160},"description":"同一种操作需要作用于多个明确来源时使用；每个 ID 都必须来自当前选区或论文读取结果。"},
            "content":{"type":"string","maxLength":2000,"description":"仅 NOTE 或 COMMENT 需要；填写用户要求保存的笔记或评论内容。"},
            "color":{"type":"string","maxLength":16,"description":"可选的标注颜色，建议使用六位 CSS 十六进制颜色，例如 #ffee58。"},
            "answer":__ANSWER_SCHEMA__,
            "operations":{"type":"array","minItems":1,"maxItems":8,"description":"一次用户请求中的完整页面操作清单。不同操作类型必须分别列出；系统会统一校验并等待所有客户端回执。","items":__OPERATION_SCHEMA__}},
            "required":[],"additionalProperties":false}
            """.replace("__OPERATION_SCHEMA__", OPERATION_SCHEMA)
            .replace("__ANSWER_SCHEMA__", ANSWER_SCHEMA);

    private final PaperActionResolver actionResolver;

    public PaperActionSkillTool(PaperActionResolver actionResolver) {
        this.actionResolver = actionResolver;
    }

    public List<AgentToolDefinition> definitions() {
        return List.of(new AgentToolDefinition(TOOL_NAME,
                "在加载适用的 paper-action Skill 后，执行经过校验的页面操作；一次请求包含不同操作类型时，必须在 operations 中完整列出，不能只执行第一项。用户同时要求回答问题时，在同一次调用中填写 answer；answer 结构与 submit_answer 相同，并引用操作目标来源。纯操作请求不要填写 answer。",
                ACTION_SCHEMA));
    }

    public boolean supports(String toolName) {
        return TOOL_NAME.equals(toolName);
    }

    public List<PreparedAction> prepare(PaperSourceCatalog catalog, Set<String> readableSourceIds,
                                        String argumentsJson, ObjectMapper objectMapper) {
        if (catalog == null) throw new IllegalArgumentException("论文来源尚未就绪");
        if (actionResolver == null) throw new IllegalStateException("页面操作能力不可用");
        try {
            JsonNode args = objectMapper.readTree(argumentsJson);
            List<PreparedAction> actions = new java.util.ArrayList<>();
            JsonNode operations = args.path("operations");
            if (operations.isArray() && !operations.isEmpty()) {
                for (int operationIndex = 0; operationIndex < operations.size(); operationIndex++) {
                    actions.addAll(prepareOperation(catalog, readableSourceIds, operations.get(operationIndex), operationIndex));
                }
            } else {
                actions.addAll(prepareOperation(catalog, readableSourceIds, args, -1));
            }
            if (actions.isEmpty()) throw new IllegalArgumentException("至少需要提供一项页面操作");
            if (actions.size() > MAX_BATCH_TARGETS) {
                throw new IllegalArgumentException("一次页面操作最多处理 " + MAX_BATCH_TARGETS + " 个物理目标");
            }
            return List.copyOf(actions);
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
        return new PreparedAction(type, actionResolver.resolve(catalog, sourceId), content, color, -1);
    }

    private List<PreparedAction> prepareOperation(PaperSourceCatalog catalog, Set<String> readableSourceIds,
                                                   JsonNode operation, int operationIndex) {
        if (operation == null || !operation.isObject()) {
            throw new IllegalArgumentException("每项页面操作必须是对象");
        }
        PaperActionType type = actionType(requiredText(operation, "actionType"));
        List<String> sourceIds = sourceIds(operation);
        if (sourceIds.size() > MAX_BATCH_TARGETS) {
            throw new IllegalArgumentException("一次页面操作最多处理 " + MAX_BATCH_TARGETS + " 个目标");
        }
        if ((type == PaperActionType.JUMP || type == PaperActionType.NOTE || type == PaperActionType.COMMENT)
                && sourceIds.size() != 1) {
            throw new IllegalArgumentException("跳转、笔记和批注一次只能指定一个目标");
        }
        String content = optionalText(operation.path("content").asText(null));
        String color = optionalText(operation.path("color").asText(null));
        List<PreparedAction> actions = new java.util.ArrayList<>();
        for (String sourceId : sourceIds) {
            if (!readableSourceIds.contains(sourceId)) {
                throw new IllegalArgumentException("操作目标来源尚未读取或未在当前选区中：" + sourceId);
            }
            PreparedAction prepared = prepareTrusted(catalog, type, sourceId, content, color);
            actions.add(new PreparedAction(prepared.type(), prepared.target(), prepared.content(),
                    prepared.color(), operationIndex));
        }
        return List.copyOf(actions);
    }

    private static List<String> sourceIds(JsonNode args) {
        Set<String> values = new java.util.LinkedHashSet<>();
        JsonNode many = args.path("sourceObjectIds");
        if (many.isArray()) {
            many.forEach(value -> {
                String sourceId = optionalText(value.asText(null));
                if (sourceId != null) values.add(sourceId);
            });
        }
        String single = optionalText(args.path("sourceObjectId").asText(null));
        if (values.isEmpty() && single != null) values.add(single);
        if (values.isEmpty()) throw new IllegalArgumentException("需要提供 sourceObjectId 或 sourceObjectIds");
        return List.copyOf(values);
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

    public record PreparedAction(PaperActionType type, ActionTarget target, String content, String color,
                                 int operationIndex) { }
}
