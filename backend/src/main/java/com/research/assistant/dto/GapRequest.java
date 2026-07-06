package com.research.assistant.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * Gap 分析请求体。
 */
@Data
public class GapRequest {

    @NotEmpty(message = "请至少选择一篇论文")
    @Size(min = 3, message = "Gap 分析至少需要 3 篇论文")
    private List<Long> paperIds;
}
