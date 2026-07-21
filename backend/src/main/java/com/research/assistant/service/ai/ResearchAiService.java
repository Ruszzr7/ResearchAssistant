package com.research.assistant.service.ai;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * 统一科研 Agent 接口 —— 基于 LangChain4j AiServices。
 * <p>
 * 暴露带 ChatMemory 的追问接口；结构化论文理解由版本化分块工作流负责。
 */
public interface ResearchAiService {

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
