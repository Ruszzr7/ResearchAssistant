package com.research.assistant.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 扩展检索请求体。
 */
@Data
public class SearchExpandRequest {

    @NotEmpty(message = "扩展查询不能为空")
    @Size(max = 50, message = "扩展查询不能超过 50 条")
    private List<@Size(max = 1000, message = "单条查询长度不能超过 1000") String> queries;
}
