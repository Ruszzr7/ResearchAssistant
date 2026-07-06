package com.research.assistant.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 论文对比请求体。
 */
@Data
public class CompareRequest {

    @NotEmpty(message = "请至少选择两篇论文")
    @Size(min = 2, max = 5, message = "对比论文数量需在 2-5 篇之间")
    private List<Long> paperIds;

    private String customDimensions;
}
