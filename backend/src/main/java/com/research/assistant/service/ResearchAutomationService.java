package com.research.assistant.service;

import com.research.assistant.entity.Paper;

import java.util.List;
import java.util.Map;

/**
 * 非对话式研究自动化能力的统一入口。
 * <p>
 * 对话与工具决策由 service.agent 下的 Agent harness 独立负责。
 */
public interface ResearchAutomationService {

    /**
     * 横向对比多篇论文。
     *
     * @param paperIds 论文 ID 列表
     * @param customDimensions 用户自定义维度（可选）
     * @return 对比结果（markdown 字符串）
     */
    String comparePapers(java.util.List<Long> paperIds, String customDimensions);

    /**
     * Gap 分析 —— 基于指定论文列表。
     *
     * @param paperIds 论文 ID 列表
     * @return Gap 分析报告（markdown 格式）
     */
    String analyzeGapsByPaperIds(java.util.List<Long> paperIds);

    /**
     * Gap 分析 —— 基于文件夹下所有论文。
     *
     * @param folderId 文件夹 ID，null 表示全库分析
     * @return Gap 分析报告（markdown 格式）
     */
    String analyzeGaps(Long folderId);

    /**
     * 对 Gap 报告进行外部验证（Agent + 工具调用）。
     *
     * @param gapReport Gap 分析报告（markdown）
     * @return 每个 Gap 的验证结果列表
     */
    java.util.List<java.util.Map<String, Object>> verifyGaps(String gapReport);

    /**
     * Agent 建议标签 —— 基于论文标题和摘要。
     */
    java.util.List<String> suggestTags(Long paperId);

    /**
     * Agent 推荐文件夹 —— 基于论文内容和现有文件夹列表。
     */
    Map<String, Object> suggestFolder(Long paperId);

    /**
     * Agent 推荐文件夹 —— 基于论文标题（导入时尚未入库的场景）。
     */
    Map<String, Object> suggestFolderByTitle(String title);

    /**
     * Agent 推荐文件夹 —— 导入前同时使用标题和摘要。
     */
    default Map<String, Object> suggestFolderByTitle(String title, String abstractText) {
        return suggestFolderByTitle(title);
    }

    /**
     * 导入前同时使用标题、摘要和关键词。关键词可帮助区分同一大类下的场景、方法或指标子目录。
     */
    default Map<String, Object> suggestFolderByTitle(String title, String abstractText, String keywords) {
        return suggestFolderByTitle(title, abstractText);
    }

    /**
     * Agent 推荐阅读状态 —— 基于论文标题和摘要。
     *
     * @return Map of {status: UNREAD/READING/READ, reason: string}
     */
    Map<String, Object> suggestReadingStatus(Long paperId);
}
