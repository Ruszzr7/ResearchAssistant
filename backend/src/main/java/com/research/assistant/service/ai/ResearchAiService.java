package com.research.assistant.service.ai;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * 统一科研 Agent 接口 —— 基于 LangChain4j AiServices。
 * <p>
 * 目前暴露论文精读、追问对话、Gap 验证等方法，后续阶段逐步扩展：
 * 对比、标签/文件夹/阅读状态推荐等。
 */
public interface ResearchAiService {

    /**
     * 深度阅读一篇论文，返回结构化分析结果。
     *
     * @param cleanedPaperText 经过清洗和截断后的 PDF 文本
     * @param researchTopic    用户当前研究主题（可为空字符串）
     * @return 结构化分析 POJO 及 token 消耗等元数据
     */
    @SystemMessage("""
            你是一位资深学术论文审稿人。请仔细阅读以下 PDF 提取的论文文本，完成结构化分析。

            **第一步：判断领域**
            先判断论文属于哪个领域，然后采用对应的分析框架：
            - AI/CV/NLP：侧重模型架构、训练策略、Benchmark 提升、Ablation 实验
            - 通信/信号处理：侧重系统模型、信道假设、理论推导、仿真设置
            - 控制/机器人：侧重控制策略、稳定性分析、实验平台
            - 其他：根据论文实际内容自适应

            **第二步：深度提取**
            除了基础信息外，必须尽可能提取：
            1. 可复现要素：核心公式（保留 LaTeX）、伪代码、源码/数据集链接、评测指标定义
            2. 实验设置：任务定义、数据集与划分、基线、评测指标、实现细节
            3. Benchmark 结果：关键指标、数值、与基线对比、来源图表

            **第三步：相关度评分（仅当用户提供了研究主题时）**
            如果用户研究主题非空，请给出 1-10 的相关度评分并说明理由。
            1 = 完全无关；10 = 高度相关，可直接借鉴。

            **第四步：输出 JSON**
            请严格按照要求的 JSON 对象输出，不要输出其他内容。

            **规则**：
            - 信息无法确定时用空数组 [] 或空字符串 ""；
            - 不要编造内容；
            - sections 按实际结构输出；
            - 若用户研究主题为空字符串，relevanceScore 必须为 null，relevanceReason 必须为 null。
            """)
    @UserMessage("""
            用户当前研究主题：{{topic}}

            请对以下论文文本进行结构化分析：

            {{text}}
            """)
    Result<PaperAnalysisResult> analyzePaper(@V("text") String cleanedPaperText,
                                                 @V("topic") String researchTopic);

    /**
     * 多轮追问。
     * <p>
     * 不通过 {@code @SystemMessage} 注入角色，而是由调用方在首次调用时把上下文作为
     * {@link dev.langchain4j.data.message.SystemMessage} 写入记忆。这样同一会话始终
     * 保留论文分析上下文，避免注解 SystemMessage 覆盖已持久化的上下文。
     */
    @UserMessage("{{question}}")
    Result<String> chat(@MemoryId String memoryId, @V("question") String question);
}
