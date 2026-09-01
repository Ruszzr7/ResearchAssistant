package com.research.assistant.service;

import java.util.Map;

/**
 * 非对话式研究自动化能力的统一入口。
 * <p>
 * 对话与工具决策由 service.agent 下的 Agent harness 独立负责。
 */
public interface ResearchAutomationService {

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

}
