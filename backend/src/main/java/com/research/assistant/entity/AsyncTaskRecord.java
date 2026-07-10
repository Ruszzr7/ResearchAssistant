package com.research.assistant.entity;

import com.baomidou.mybatisplus.annotation.*;

import java.time.LocalDateTime;

/**
 * 异步任务持久化记录。
 *
 * <p>任务状态从内存同步写入本表，保证后端重启后仍可查询已完成任务，
 * 并能对重启前未完成的任务进行安全结算。</p>
 */
@TableName("async_task")
public class AsyncTaskRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 任务唯一标识（UUID） */
    @TableField("task_id")
    private String taskId;

    /** PENDING / PROCESSING / COMPLETED / FAILED / CANCELLED */
    private String status;

    /** 工作流模板 key，普通任务为空 */
    @TableField("workflow_type")
    private String workflowType;

    /** 工作流启动上下文 JSON */
    @TableField("context_json")
    private String contextJson;

    /** 任务展示标题 */
    private String title;

    /** 当前阶段文案，供前端展示进度 */
    @TableField("stage_text")
    private String stageText;

    /** 任务成功结果的 JSON 字符串 */
    @TableField("result_json")
    private String resultJson;

    /** 失败时的错误信息 */
    private String error;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    public AsyncTaskRecord() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getWorkflowType() { return workflowType; }
    public void setWorkflowType(String workflowType) { this.workflowType = workflowType; }

    public String getContextJson() { return contextJson; }
    public void setContextJson(String contextJson) { this.contextJson = contextJson; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getStageText() { return stageText; }
    public void setStageText(String stageText) { this.stageText = stageText; }

    public String getResultJson() { return resultJson; }
    public void setResultJson(String resultJson) { this.resultJson = resultJson; }

    public String getError() { return error; }
    public void setError(String error) { this.error = error; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
