package com.research.assistant.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 增加阅读时长请求。
 */
@Data
public class ReadingTimeRequest {

    @NotNull(message = "阅读时长不能为空")
    @Min(value = 1, message = "阅读时长必须大于等于 1 秒")
    private Integer seconds;
}
