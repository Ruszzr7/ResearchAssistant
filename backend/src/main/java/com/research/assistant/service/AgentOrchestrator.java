package com.research.assistant.service;

import com.research.assistant.entity.Paper;
import com.research.assistant.entity.PaperAnalysis;

import java.util.List;
import java.util.Map;

/**
 * Agent 编排器 —— 所有 Agent 能力的统一调度入口。
 * <p>
 * 每个方法封装一次完整的 Agent 任务：准备数据 → 调用 LLM → 解析结果 → 持久化。
 */
public interface AgentOrchestrator {

    /**
     * 深度阅读一篇论文 —— 完整处理流水线。
     *
     * @param paperId 论文 ID
     * @return 结构化分析结果
     */
    PaperAnalysis processPaper(Long paperId);

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
     * 对话追问 —— 基于分析上下文回答用户问题（单轮，无记忆）。
     */
    String chatAbout(String context, String question);

    /**
     * 对话追问 —— 基于分析上下文回答用户问题（多轮记忆）。
     *
     * @param conversationId 会话标识，为空则退化为单轮
     * @param context 初次提问时的分析上下文
     * @param question 用户问题
     */
    String chatAbout(String conversationId, String context, String question);

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
     * Agent 推荐阅读状态 —— 基于论文标题和摘要。
     *
     * @return Map of {status: UNREAD/READING/READ, reason: string}
     */
    Map<String, Object> suggestReadingStatus(Long paperId);
}
