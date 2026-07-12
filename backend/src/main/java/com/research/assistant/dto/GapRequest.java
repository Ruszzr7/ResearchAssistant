package com.research.assistant.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class GapRequest {
    @NotEmpty(message = "至少选择一篇论文")
    @Size(min = 3, max = 50, message = "Gap 分析论文数量必须在 3-50 篇之间")
    private List<Long> paperIds;
}
