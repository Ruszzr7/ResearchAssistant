package com.research.assistant.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 更新阅读当前页请求。
 */
@Data
public class ReadingProgressUpdateRequest {

    @NotNull(message = "当前页不能为空")
    @Min(value = 1, message = "当前页必须大于等于 1")
    private Integer currentPage;
}
