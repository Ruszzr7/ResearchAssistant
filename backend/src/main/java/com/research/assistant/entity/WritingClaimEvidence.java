package com.research.assistant.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("writing_claim_evidence")
public class WritingClaimEvidence {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long claimId;
    private Long paperId;
    private Long researchSessionId;
    private String relationType;
    private Integer pageNumber;
    private String locator;
    private String quoteText;
    private String note;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
