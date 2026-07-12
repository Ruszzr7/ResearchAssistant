package com.research.assistant.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class CompareRequest {
    @NotEmpty(message = "至少选择两篇论文")
    @Size(min = 2, max = 5, message = "对比论文数量必须在 2-5 篇之间")
    private List<Long> paperIds;

    @Size(max = 2000, message = "自定义维度长度不能超过 2000")
    private String customDimensions;
}
