package com.research.assistant.service.ai;

import dev.langchain4j.service.Result;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

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
}
