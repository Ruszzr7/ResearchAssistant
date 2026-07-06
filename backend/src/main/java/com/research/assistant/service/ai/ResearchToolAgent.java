package com.research.assistant.service.ai;

import dev.langchain4j.service.Result;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

import static com.research.assistant.service.ai.SuggestionPojos.*;

/**
 * 工具型科研 Agent 接口 —— 用于需要 LLM 自主调用工具但不需要对话记忆的任务。
 * <p>
 * 与 {@link ResearchAiService} 分开配置，不绑定 {@code ChatMemoryProvider}，
 * 避免工具调用任务受历史消息污染或触发记忆相关异常。
 */
public interface ResearchToolAgent {

    /**
     * 对 Gap 分析报告进行外部验证。
     * <p>
     * LLM 可调用 {@code searchArxiv} / {@code fetchCrossref} / {@code extractPdfTextByPath} 等工具
     * 检索每个 Gap 是否已有相关研究，返回 JSON 数组：
     * 每个元素含 gapTitle、level（red/yellow/green）、evidence、relatedPapers。
     */
    @SystemMessage("""
            你是一位严谨的学术验证助手。对下面 Gap 分析报告中的每个 Gap，请使用可用工具检索 arXiv 和相关数据库，
            判断该 Gap 是否已被研究。

            **判断标准**：
            - red（红色）：未发现相关研究，Gap 很可能成立；
            - yellow（黄色）：发现少量相关工作，但不够充分，Gap 部分成立；
            - green（绿色）：已有较多相关研究，Gap 可能已被覆盖。

            **输出要求**：
            只返回一个 JSON 数组，不要 markdown 代码块，不要其他解释。每个元素格式：
            {
              "gapTitle": "Gap 标题",
              "level": "red|yellow|green",
              "evidence": "一句话证据说明",
              "relatedPapers": ["论文标题1", "论文标题2"]
            }
            """)
    @UserMessage("请验证以下 Gap 分析报告：\n\n{{it}}")
    Result<String> verifyGaps(String gapReport);

    /**
     * 标签建议。
     */
    @SystemMessage("你是一位学术文献分类专家。请根据论文标题和摘要建议 3-5 个精准的技术关键词标签。")
    @UserMessage("论文标题：{{title}}\n摘要：{{abstract}}\n\n请返回 JSON：{\"tags\":[\"tag1\", \"tag2\", ...]}")
    Result<TagSuggestionResult> suggestTags(@V("title") String title, @V("abstract") String abstractText);

    /**
     * 文件夹推荐。
     */
    @SystemMessage("你是一位学术文献管理助手。请从现有文件夹中为论文推荐最合适的位置。")
    @UserMessage("论文标题：{{title}}\n摘要：{{abstract}}\n\n现有文件夹列表：\n{{folders}}\n\n请返回 JSON：{\"folderId\": 数字或null, \"reason\": \"一句话理由\", \"suggestNew\": true/false, \"newName\": \"建议新文件夹名\"}")
    Result<FolderSuggestionResult> suggestFolder(@V("title") String title,
                                                  @V("abstract") String abstractText,
                                                  @V("folders") String folderList);

    /**
     * 阅读状态推荐。
     */
    @SystemMessage("你是一位科研阅读管理助手。根据论文标题、摘要以及用户近期阅读行为，推荐阅读状态。")
    @UserMessage("论文标题：{{title}}\n摘要：{{abstract}}\n\n请返回 JSON：{\"status\": \"UNREAD|READING|READ\", \"reason\": \"一句话理由\"}")
    Result<ReadingStatusSuggestionResult> suggestReadingStatus(@V("title") String title,
                                                                @V("abstract") String abstractText);
}
