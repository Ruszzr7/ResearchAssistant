package com.research.assistant.service.ai.skill.io;

/**
 * 文件夹推荐 Skill 输入。
 *
 * <p>paperId 非空时基于已有论文推荐；否则基于 title、摘要和关键词在导入前推荐。</p>
 */
public record SuggestFolderInput(Long paperId, String title, String abstractText, String keywords) {

    public SuggestFolderInput(Long paperId, String title, String abstractText) {
        this(paperId, title, abstractText, null);
    }

    public SuggestFolderInput(Long paperId, String title) {
        this(paperId, title, null, null);
    }
}
