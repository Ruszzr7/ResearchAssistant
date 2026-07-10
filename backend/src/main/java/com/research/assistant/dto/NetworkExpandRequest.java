package com.research.assistant.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 引用网络扩展请求。
 */
public class NetworkExpandRequest {

    /** 本地论文 ID（与 s2PaperId 二选一） */
    private Long paperId;

    /** 原始 Semantic Scholar paperId（与 paperId 二选一） */
    private String s2PaperId;

    /** 扩展方向：forward / backward / author */
    private List<String> directions;

    /** 每个方向最大返回数 */
    @NotNull
    @Min(1)
    private int limit = 10;

    public Long getPaperId() {
        return paperId;
    }

    public void setPaperId(Long paperId) {
        this.paperId = paperId;
    }

    public String getS2PaperId() {
        return s2PaperId;
    }

    public void setS2PaperId(String s2PaperId) {
        this.s2PaperId = s2PaperId;
    }

    public List<String> getDirections() {
        return directions;
    }

    public void setDirections(List<String> directions) {
        this.directions = directions;
    }

    public int getLimit() {
        return limit;
    }

    public void setLimit(int limit) {
        this.limit = limit;
    }
}
