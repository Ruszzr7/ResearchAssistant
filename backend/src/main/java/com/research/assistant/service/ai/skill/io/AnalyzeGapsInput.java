package com.research.assistant.service.ai.skill.io;

import java.util.List;

/**
 * Gap 分析 Skill 输入。
 *
 * <p>优先使用 paperIds；若 paperIds 为空则按 folderId 查询。
 * folderId 为 null 表示全库分析。</p>
 */
public record AnalyzeGapsInput(List<Long> paperIds, Long folderId) {
}
