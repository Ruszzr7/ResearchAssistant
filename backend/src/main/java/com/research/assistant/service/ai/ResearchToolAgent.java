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
     * LLM 可调用 {@code searchArxiv} / {@code searchSemanticScholar} / {@code fetchCrossref} / {@code extractPdfTextByPath} 等工具
     * 检索每个 Gap 是否已有相关研究，返回 JSON 数组：
     * 每个元素含 gapTitle、level（red/yellow/green）、reason、evidence。
     */
    @SystemMessage("""
            你是一位严谨的学术验证助手。对下面 Gap 分析报告中的每个 Gap，请使用可用工具检索 arXiv 和 Semantic Scholar，
            判断该 Gap 是否已被研究。

            **判断标准**（基于证据质量，而非单纯结果数量）：
            - red（红色）：未发现相关研究，或候选论文与 Gap 仅有表面关键词重叠但并未真正解决该问题；Gap 很可能成立；
            - yellow（黄色）：发现少量相关工作，方法或场景部分相关，但不够充分；Gap 部分成立；
            - green（绿色）：已有明确相关研究直接针对该 Gap，或已有方法/数据集/场景高度重合；Gap 可能已被覆盖。

            **工具使用建议**：
            1. 先用 2-3 个不同关键词分别搜索 arXiv 和 Semantic Scholar；
            2. 阅读候选论文标题和摘要，判断其是否真正与 Gap 相关；
            3. 对最相关的 1-3 篇，可选择性提取 PDF 正文进一步验证。

            **输出要求**：
            只返回一个 JSON 数组，不要 markdown 代码块，不要其他解释。每个元素格式：
            {
              "gapTitle": "Gap 标题",
              "level": "red|yellow|green",
              "reason": "一句话结论，说明为什么是该等级",
              "evidence": [
                {
                  "title": "论文标题",
                  "source": "arXiv 或 Semantic Scholar",
                  "year": "2024",
                  "snippet": "与该 Gap 相关的摘要或正文片段（50-100字）",
                  "url": "论文链接"
                }
              ]
            }
            """)
    @UserMessage("请验证以下 Gap 分析报告：\n\n{{it}}")
    Result<String> verifyGaps(String gapReport);

    /**
     * 标签建议。
     */
    @SystemMessage("你是一位学术文献分类专家。请根据论文标题、摘要以及从用户论文库召回的相关片段，建议 3-5 个精准的技术关键词标签。")
    @UserMessage("""
            论文标题：{{title}}
            摘要：{{abstract}}

            用户论文库中相关片段：
            {{relatedSnippets}}

            请返回 JSON：{"tags":["tag1", "tag2", ...]}
            """)
    Result<TagSuggestionResult> suggestTags(@V("title") String title,
                                            @V("abstract") String abstractText,
                                            @V("relatedSnippets") String relatedSnippets);

    /**
     * 文件夹推荐。
     */
    @SystemMessage("你是一位学术文献管理助手。请从现有文件夹中为论文推荐最合适的位置，可参考用户论文库中的相关片段。")
    @UserMessage("""
            论文标题：{{title}}
            摘要：{{abstract}}

            现有文件夹列表：
            {{folders}}

            用户论文库中相关片段：
            {{relatedSnippets}}

            请返回 JSON：{"folderId": 数字或null, "reason": "一句话理由", "suggestNew": true/false, "newName": "建议新文件夹名"}
            """)
    Result<FolderSuggestionResult> suggestFolder(@V("title") String title,
                                                  @V("abstract") String abstractText,
                                                  @V("folders") String folderList,
                                                  @V("relatedSnippets") String relatedSnippets);

    /**
     * 阅读状态推荐。
     */
    @SystemMessage("你是一位科研阅读管理助手。根据论文标题、摘要以及用户论文库中相关片段，推荐阅读状态。")
    @UserMessage("""
            论文标题：{{title}}
            摘要：{{abstract}}

            用户论文库中相关片段：
            {{relatedSnippets}}

            请返回 JSON：{"status": "UNREAD|READING|READ", "reason": "一句话理由"}
            """)
    Result<ReadingStatusSuggestionResult> suggestReadingStatus(@V("title") String title,
                                                               @V("abstract") String abstractText,
                                                               @V("relatedSnippets") String relatedSnippets);

    /**
     * 多论文横向对比。
     *
     * @param context        由各论文标题、年份、来源、分析结果拼接的上下文
     * @param customDimensions 用户指定的自定义对比维度，可为空
     * @return markdown 格式对比报告（含表格）
     */
    @SystemMessage("""
            你是一位资深学术研究者。请对以下多篇论文进行横向对比分析。

            **第一步**：先识别这几篇论文的**共同维度和差异点**，确定最具分析价值的对比角度。

            **第二步**：按识别出的维度生成对比报告（markdown，含表格）。参考维度：
            研究问题差异 / 方法论异同 / 实验设置与数据集 / 性能对比（如可比较）/ 各自优势与局限 / 改进方向

            **输出要求**：
            - markdown 格式，包含表格
            - 深度分析差异背后的原因，不只罗列事实
            - 如信息不足，诚实说明
            - 如用户指定了自定义维度，优先使用用户指定的维度
            """)
    @UserMessage("请对以下论文集合进行对比分析：\n\n{{context}}\n\n用户指定对比维度（可为空）：{{customDimensions}}")
    Result<String> comparePapers(@V("context") String context,
                                  @V("customDimensions") String customDimensions);

    /**
     * 研究 Gap 分析。
     *
     * @param context 论文集合的标题、年份、核心贡献、方法类型、方法概述、局限性等拼接文本
     * @return markdown 格式 Gap 分析报告
     */
    @SystemMessage("""
            你是一位资深学术研究者，正在对一个研究领域进行系统性文献综述。

            **任务**：阅读以下论文集合的信息，识别该领域的研究空白（Research Gaps）和未来方向。

            **分析维度**（每个 Gap 必须归类到以下维度之一）：
            1. **方法 Gap**：主流方法/算法有什么已知缺陷？有没有被忽视的替代思路？
            2. **场景/数据 Gap**：现有实验在什么条件下做的？什么场景没人研究过？
            3. **理论 Gap**：哪些结论缺乏理论证明？方法背后的理论基础是否薄弱？
            4. **比较 Gap**：几篇论文的方法有没有被公平比较过？缺乏统一的 benchmark？
            5. **交叉 Gap**：有没有其他领域的方法可以迁移过来？跨领域的技术能否适用？

            **输出格式**（markdown，每个 Gap 包含）：
            - ### [维度标签] Gap 标题
              - **描述**: Gap 是什么
              - **为什么是 Gap**: 现有方法为什么没解决
              - **潜在价值**: 高/中/低
              - **可行方向**: 建议的研究思路
              - **验证建议**: 推荐用什么关键词去 arXiv 验证这个 Gap 是否已被研究

            至少输出 3 个 Gap，覆盖至少 3 个不同维度。对每个 Gap 诚实评估其不确定性。
            """)
    @UserMessage("请对以下论文集合进行 Gap 分析：\n\n{{context}}")
    Result<String> analyzeGaps(@V("context") String context);
}
