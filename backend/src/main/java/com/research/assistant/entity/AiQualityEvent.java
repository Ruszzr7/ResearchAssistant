package com.research.assistant.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 单次 AI 结构化输出质量事件，不保存完整 Prompt 或论文原文。 */
@Data
@TableName("ai_quality_event")
public class AiQualityEvent {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("paper_id")
    private Long paperId;

    @TableField("task_type")
    private String taskType;

    private String stage;

    @TableField("prompt_version")
    private String promptVersion;

    @TableField("model_name")
    private String modelName;

    /** PASS / REPAIRED / FALLBACK / FAILED */
    private String status;

    private Boolean repaired;

    @TableField("retry_count")
    private Integer retryCount;

    @TableField("validation_errors_json")
    private String validationErrorsJson;

    @TableField("error_message")
    private String errorMessage;

    @TableField("prompt_tokens")
    private Integer promptTokens;

    @TableField("completion_tokens")
    private Integer completionTokens;

    @TableField("total_tokens")
    private Integer totalTokens;

    @TableField("latency_ms")
    private Long latencyMs;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
