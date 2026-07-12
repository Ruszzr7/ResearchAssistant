package com.research.assistant.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class SearchExecuteRequest {
    @NotEmpty(message = "至少提供一个英文检索词")
    @Size(max = 50, message = "检索词数量不能超过 50")
    @Valid
    @JsonProperty("keywords_en")
    private List<@Size(max = 200, message = "检索词长度不能超过 200") String> keywordsEn;

    @Size(max = 500, message = "领域长度不能超过 500")
    private String domain;
    @JsonProperty("sub_direction")
    @Size(max = 500, message = "子方向长度不能超过 500")
    private String subDirection;
    @JsonProperty("keywords_cn")
    @Size(max = 50, message = "中文关键词数量不能超过 50")
    private List<@Size(max = 200, message = "中文关键词长度不能超过 200") String> keywordsCn;
    @JsonProperty("time_range")
    @Size(max = 100, message = "时间范围长度不能超过 100")
    private String timeRange;
    @JsonProperty("paper_type")
    @Size(max = 20, message = "论文类型数量不能超过 20")
    private List<@Size(max = 100, message = "论文类型长度不能超过 100") String> paperType;
    @Size(max = 2_000, message = "说明长度不能超过 2000")
    private String explanation;

    public Map<String, Object> toParams() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("keywords_en", keywordsEn);
        if (domain != null) params.put("domain", domain);
        if (subDirection != null) params.put("sub_direction", subDirection);
        if (keywordsCn != null) params.put("keywords_cn", keywordsCn);
        if (timeRange != null) params.put("time_range", timeRange);
        if (paperType != null) params.put("paper_type", paperType);
        if (explanation != null) params.put("explanation", explanation);
        return params;
    }

    public List<String> getKeywordsEn() { return keywordsEn; }
    public void setKeywordsEn(List<String> keywordsEn) { this.keywordsEn = keywordsEn; }
    public String getDomain() { return domain; }
    public void setDomain(String domain) { this.domain = domain; }
    public String getSubDirection() { return subDirection; }
    public void setSubDirection(String subDirection) { this.subDirection = subDirection; }
    public List<String> getKeywordsCn() { return keywordsCn; }
    public void setKeywordsCn(List<String> keywordsCn) { this.keywordsCn = keywordsCn; }
    public String getTimeRange() { return timeRange; }
    public void setTimeRange(String timeRange) { this.timeRange = timeRange; }
    public List<String> getPaperType() { return paperType; }
    public void setPaperType(List<String> paperType) { this.paperType = paperType; }
    public String getExplanation() { return explanation; }
    public void setExplanation(String explanation) { this.explanation = explanation; }
}
