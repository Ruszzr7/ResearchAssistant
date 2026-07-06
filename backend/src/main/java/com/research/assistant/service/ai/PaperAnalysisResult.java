package com.research.assistant.service.ai;

import dev.langchain4j.model.output.structured.Description;
import lombok.Data;

import java.util.List;

/**
 * 论文精读结构化分析结果 —— 供 LangChain4j AiServices 直接反序列化。
 * <p>
 * 字段与 {@link com.research.assistant.entity.PaperAnalysis} 一一对应，
 * 通过 {@link com.research.assistant.service.PaperProcessingService} 映射后持久化。
 */
@Data
public class PaperAnalysisResult {

    @Description("论文所属领域，如 AI / CV / NLP / 通信 / 控制 / 其他")
    private String domain;

    @Description("用三句话概括论文的核心贡献：问题是什么、怎么解决的、效果如何")
    private String coreContribution;

    @Description("研究方法类型，只能从以下四项中选择：THEORETICAL / EXPERIMENTAL / SYSTEM / SURVEY")
    private String methodType;

    @Description("方法概述：使用了什么模型/算法/框架，关键设计是什么")
    private String methodSummary;

    @Description("论文章节结构列表")
    private List<Section> sections;

    @Description("论文使用的数据集名称列表")
    private List<String> datasets;

    @Description("论文使用的模型或算法名称列表")
    private List<String> models;

    @Description("论文的主要实验发现或理论结果列表")
    private List<String> keyFindings;

    @Description("论文自述的局限性和推断的潜在问题列表")
    private List<String> limitations;

    @Description("论文中表格的摘要列表")
    private List<TableSummary> tablesSummary;

    @Description("论文中图表的摘要列表")
    private List<FigureSummary> figuresSummary;

    @Data
    public static class Section {
        @Description("章节标题")
        private String heading;

        @Description("该章节的一句话摘要")
        private String summary;
    }

    @Data
    public static class TableSummary {
        @Description("表格标题或编号")
        private String caption;

        @Description("表格大致内容描述")
        private String contentHint;
    }

    @Data
    public static class FigureSummary {
        @Description("图表标题或编号")
        private String caption;
    }
}
