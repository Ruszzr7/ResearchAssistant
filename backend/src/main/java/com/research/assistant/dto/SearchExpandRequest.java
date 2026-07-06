package com.research.assistant.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * 扩展检索请求体。
 */
@Data
public class SearchExpandRequest {

    @NotEmpty(message = "扩展查询不能为空")
    private List<String> queries;
}
