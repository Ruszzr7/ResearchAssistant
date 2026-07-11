package com.research.assistant.service.ai;

import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

/**
 * 科研 Agent 的 Spring 配置。
 * <p>
 * 采用编程式 {@link AiServices#builder(Class)} 构建代理，而不是 starter 的
 * {@code @AiService} 自动扫描，因为 LLM 配置需要从 DB 的 settings 表运行时读取。
 * Bean 使用懒加载，保证首次启动时即使尚未填写模型配置，也能先进入设置页面。
 */
@Configuration
public class ResearchAiConfig {

    @Bean
    @Lazy
    public ResearchToolAgent researchToolAgent(LangChain4jModelFactory modelFactory,
                                                ResearchTools researchTools) {
        return AiServices.builder(ResearchToolAgent.class)
                .chatModel(modelFactory.createChatModel())
                .tools(researchTools)
                .build();
    }

    @Bean
    @Lazy
    public ResearchAiService researchAiService(LangChain4jModelFactory modelFactory,
                                                ChatMemoryStore chatMemoryStore) {
        return AiServices.builder(ResearchAiService.class)
                .chatModel(modelFactory.createChatModel())
                .chatMemoryProvider(memoryId -> MessageWindowChatMemory.builder()
                        .id(memoryId)
                        .maxMessages(20)
                        .chatMemoryStore(chatMemoryStore)
                        .build())
                .build();
    }
}
