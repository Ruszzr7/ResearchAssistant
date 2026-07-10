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
        this.definitions = Map.of(
                "gap-research", gapResearch(),
                "paper-import", paperImport(),
                "literature-survey", literatureSurvey()
        );
    }

    public WorkflowDefinition get(String key) {
        return definitions.get(key);
    }

    public List<WorkflowDefinition> all() {
        return List.copyOf(definitions.values());
    }

    private static WorkflowDefinition gapResearch() {
        Map<String, Object> analyzeArgs = new HashMap<>();
        analyzeArgs.put("paperIds", "{{context.paperIds}}");
        analyzeArgs.put("folderId", null);

        return new WorkflowDefinition(
                "gap-research",
                "研究空白分析",
                "基于论文列表识别研究空白，并通过 arXiv/Crossref 等外部来源验证每个 Gap 是否已被研究。",
                List.of(
                        new WorkflowStepDefinition(
                                "库内 Gap 分析",
                                Skills.ANALYZE_GAPS,
                                analyzeArgs,
                                "gaps"
                        ),
                        new WorkflowStepDefinition(
                                "外部验证",
                                Skills.VERIFY_GAPS,
                                Map.of("gapReport", "{{prev}}"),
                                "verified"
                        )
                )
        );
    }

    private static WorkflowDefinition paperImport() {
        Map<String, Object> folderArgs = new HashMap<>();
        folderArgs.put("paperId", "{{context.paperId}}");
        folderArgs.put("title", null);

        return new WorkflowDefinition(
                "paper-import",
                "论文入库流水线",
                "为已上传 PDF 的论文自动补全元数据、推荐标签/文件夹/阅读状态并完成深度分析。",
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
                        ),
                        new WorkflowStepDefinition(
                                "阅读状态推荐",
                                Skills.SUGGEST_READING_STATUS,
                                Map.of("paperId", "{{context.paperId}}"),
                                "readingStatus"
                        ),
                        new WorkflowStepDefinition(
                                "深度分析",
                                Skills.ANALYZE_PAPER,
                                Map.of("paperId", "{{context.paperId}}"),
                                "analysis"
                        )
                )
        );
    }

    private static WorkflowDefinition literatureSurvey() {
        Map<String, Object> importArgs = new HashMap<>();
        importArgs.put("selected", "{{input.selected}}");
        importArgs.put("folderId", "{{input.folderId}}");

        return new WorkflowDefinition(
                "literature-survey",
                "文献调研",
                "把自然语言检索目标转换为检索要素，多源检索并去重，等待用户确认后批量导入本地文库。",
                List.of(
                        new WorkflowStepDefinition(
                                "提炼检索要素",
                                Skills.EXTRACT_SEARCH_ELEMENTS,
                                Map.of("query", "{{context.query}}"),
                                "elements"
                        ),
                        new WorkflowStepDefinition(
                                "多源检索与去重",
                                Skills.MULTI_SOURCE_SEARCH,
                                Map.of("elements", "{{prev}}"),
                                "candidates"
                        ),
                        new WorkflowStepDefinition(
                                "等待用户确认",
                                Skills.PREPARE_SURVEY_CONFIRMATION,
                                Map.of("candidates", "{{prev}}"),
                                "pendingSelection",
                                true
                        ),
                        new WorkflowStepDefinition(
                                "批量入库",
                                Skills.IMPORT_SELECTED_PAPERS,
                                importArgs,
                                "imported"
                        )
                )
        );
    }
}
