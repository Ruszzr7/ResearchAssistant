package com.research.assistant.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 用户显式切换论文阅读状态的请求。
 */
@Data
public class ReadingStatusUpdateRequest {

    @NotBlank(message = "阅读状态不能为空")
    private String status;
}
