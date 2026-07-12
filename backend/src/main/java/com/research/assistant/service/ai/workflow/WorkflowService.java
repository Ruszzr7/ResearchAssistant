package com.research.assistant.service.ai.workflow;

import com.research.assistant.service.async.AsyncTaskResult;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 工作流服务 —— 对外提供固定工作流的提交与查询。
 */
@Service
public class WorkflowService {

    private final WorkflowEngine workflowEngine;

    public WorkflowService(WorkflowEngine workflowEngine) {
        this.workflowEngine = workflowEngine;
    }

    /**
     * 提交 Gap Research 工作流：库内 Gap 分析 → 外部验证。
     */
    public String submitGapResearch(List<Long> paperIds) {
        return workflowEngine.submitRecoverable("gap-research", Map.of("paperIds", paperIds));
    }

    /**
     * 提交文献调研流水线。
     */
    public String submitLiteratureSurvey(String query) {
        return workflowEngine.submitRecoverable("literature-survey", Map.of("query", query));
    }

    /**
     * 用户确认后继续工作流。
     */
    public String confirm(String taskId, Map<String, Object> userInput) {
        return workflowEngine.confirmRecoverable(taskId, userInput);
    }

    /**
     * 提交论文入库流水线：元数据补全 → 标签/文件夹/阅读状态推荐 → 深度分析。
     */
    public String submitPaperImport(Long paperId) {
        return workflowEngine.submitRecoverable("paper-import", Map.of("paperId", paperId));
    }

    /**
     * 查询工作流任务结果。
     */
    public AsyncTaskResult<?> get(String taskId) {
        return workflowEngine.get(taskId);
    }

    /**
     * 从失败点重试工作流。
     */
    public String retry(String taskId) {
        return workflowEngine.retryRecoverable(taskId);
    }
}
