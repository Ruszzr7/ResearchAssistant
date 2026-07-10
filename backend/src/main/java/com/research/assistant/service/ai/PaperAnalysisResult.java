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

    @Description("可复现要素：核心公式、伪代码、源码链接、数据集、评测指标等")
    private List<ReproducibleArtifact> reproducibleArtifacts;

    @Description("实验设置：任务定义、数据集划分、基线方法、评测指标、实现细节")
    private ExperimentSetup experimentSetup;

    @Description("Benchmark / 实验结果列表：指标、数值、与基线对比、来源图表")
    private List<BenchmarkResult> benchmarkResults;

    @Description("与用户研究主题的相关度评分（1-10）。仅当提供了研究主题时才填写，否则必须为 null")
    private Integer relevanceScore;

    @Description("相关度评分理由，1-2句话说明论文与用户主题为何相关/不相关")
    private String relevanceReason;

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

    @Data
    public static class ReproducibleArtifact {
        @Description("类型：FORMULA / PSEUDOCODE / SOURCE_CODE / DATASET / METRIC / OTHER")
        private String type;

        @Description("短标题或名称，如 'Eq.4' / 'Algorithm 1' / 'GitHub URL'")
        private String title;

        @Description("内容：LaTeX 公式、伪代码片段、URL、指标定义等")
        private String content;

        @Description("在论文中的位置，如 'Section 3.2' / 'Table 2'")
        private String location;
    }

    @Data
    public static class ExperimentSetup {
        @Description("论文解决的任务定义")
        private String taskDefinition;

        @Description("使用的数据集及划分方式")
        private List<String> datasets;

        @Description("对比的基线方法")
        private List<String> baselines;

        @Description("评测指标")
        private List<String> metrics;

        @Description("实现细节：硬件、框架、超参数、随机种子等")
        private String implementationDetails;
    }

    @Data
    public static class BenchmarkResult {
        @Description("指标名称，如 Accuracy / F1 / BLEU")
        private String metric;

        @Description("论文报告的值，如 '92.3%'")
        private String value;

        @Description("对应基线值（如有）")
        private String baselineValue;

        @Description("评测数据集")
        private String dataset;

        @Description("来源表格或图表，如 'Table 3'")
        private String source;

        @Description("补充说明，如 'SOTA on CIFAR-10'")
        private String note;
    }
}
