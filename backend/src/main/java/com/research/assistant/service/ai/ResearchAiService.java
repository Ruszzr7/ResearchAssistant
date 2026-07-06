package com.research.assistant.service.ai;

import dev.langchain4j.service.Result;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/**
 * 统一科研 Agent 接口 —— 基于 LangChain4j AiServices。
 * <p>
 * 目前仅暴露 Phase 1 所需的论文精读方法，后续阶段逐步扩展：
 * 对比、Gap 分析、多轮对话、标签/文件夹/阅读状态推荐等。
 */
public interface ResearchAiService {

    /**
     * 深度阅读一篇论文，返回结构化分析结果。
     *
     * @param cleanedPaperText 经过清洗和截断后的 PDF 文本
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

            **第二步：输出 JSON**
            请严格按照要求的 JSON 对象输出，不要输出其他内容。

            **规则**：信息无法确定时用空数组 [] 或空字符串 ""；不要编造内容；sections 按实际结构输出。
            """)
    @UserMessage("请对以下论文文本进行结构化分析：\n\n{{it}}")
    Result<PaperAnalysisResult> analyzePaper(String cleanedPaperText);
}
