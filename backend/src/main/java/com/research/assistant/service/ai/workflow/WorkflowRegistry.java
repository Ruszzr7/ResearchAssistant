package com.research.assistant.service.ai.workflow;

import com.research.assistant.service.ai.skill.Skills;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 工作流注册表 —— 管理所有预定义工作流模板。
 */
@Component
public class WorkflowRegistry {

    private Map<String, WorkflowDefinition> definitions;

    public WorkflowRegistry() {
        this.definitions = Map.of("paper-import", paperImport());
    }

    public WorkflowDefinition get(String key) {
        return definitions.get(key);
    }

    public List<WorkflowDefinition> all() {
        return List.copyOf(definitions.values());
    }

    private static WorkflowDefinition paperImport() {
        Map<String, Object> folderArgs = new HashMap<>();
        folderArgs.put("paperId", "{{context.paperId}}");
        folderArgs.put("title", null);

        return new WorkflowDefinition(
                "paper-import",
                "论文入库流水线",
                "为已上传 PDF 的论文自动补全元数据并推荐标签和文件夹；PDF 结构化记忆由独立后台任务并行建立。",
                List.of(
                        new WorkflowStepDefinition(
                                "元数据补全",
                                Skills.ENRICH_METADATA,
                                Map.of("paperId", "{{context.paperId}}"),
                                "metadata"
                        ),
                        new WorkflowStepDefinition(
                                "标签推荐",
                                Skills.SUGGEST_TAGS,
                                Map.of("paperId", "{{context.paperId}}"),
                                "tags"
                        ),
                        new WorkflowStepDefinition(
                                "文件夹推荐",
                                Skills.SUGGEST_FOLDER,
                                folderArgs,
                                "folder"
                        )
                )
        );
    }

}
