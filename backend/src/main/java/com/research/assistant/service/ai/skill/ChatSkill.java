package com.research.assistant.service.ai.skill;

import com.research.assistant.service.LLMService;
import com.research.assistant.service.ai.ResearchAiService;
import com.research.assistant.service.ai.skill.io.ChatInput;
import com.research.assistant.service.rag.RagRetrievalService;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.service.Result;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Lazy;

import java.util.List;

/**
 * 对话追问 Skill：支持单轮无记忆与多轮记忆两种模式，并集成 RAG 召回。
 */
@Component
public class ChatSkill implements Skill<ChatInput, String> {

    private static final Logger log = LoggerFactory.getLogger(ChatSkill.class);

    private final LLMService llmService;
    private final ResearchAiService researchAiService;
    private final ChatMemoryStore chatMemoryStore;
    private final RagRetrievalService ragRetrievalService;

    public ChatSkill(LLMService llmService,
                     @Lazy ResearchAiService researchAiService,
                     ChatMemoryStore chatMemoryStore,
                     RagRetrievalService ragRetrievalService) {
        this.llmService = llmService;
        this.researchAiService = researchAiService;
        this.chatMemoryStore = chatMemoryStore;
        this.ragRetrievalService = ragRetrievalService;
    }

    @Override
    public String name() {
        return Skills.CHAT;
    }

    @Override
    public String description() {
        return "基于论文上下文与 RAG 召回进行单轮或多轮对话追问。输入：{\"conversationId\": \"可选会话ID\", \"context\": \"论文分析上下文\", \"question\": \"用户问题\"}；输出：String。";
    }

    @Override
    public Class<ChatInput> inputType() {
        return ChatInput.class;
    }

    @Override
    public String execute(SkillContext ctx, ChatInput input) {
        String conversationId = input.conversationId();
        String context = input.context();
        String question = input.question();

        String ragContext = ragRetrievalService.retrieveAndRerankAsContext(question, 10, 0.65);

        if (conversationId == null || conversationId.isBlank()) {
            return chatWithoutMemory(context, question, ragContext);
        }

        List<ChatMessage> messages = chatMemoryStore.getMessages(conversationId);
        if (messages.isEmpty()) {
            String systemContent = buildSystemContent(context, ragContext);
            chatMemoryStore.updateMessages(conversationId, List.of(SystemMessage.from(systemContent)));
        } else if (!ragContext.isBlank()) {
            chatMemoryStore.updateMessages(conversationId,
                    List.of(SystemMessage.from("本次问题相关片段：\n" + ragContext)));
        }

        Result<String> result = researchAiService.chat(conversationId, question);
        return result != null ? result.content() : "";
    }

    private String buildSystemContent(String context, String ragContext) {
        String systemContent = """
                你是一位学术研究助手，帮助用户深入理解论文分析结果。回答简洁专业；
                如果上下文中没有相关信息，诚实说明。
                """;
        if (context != null && !context.isBlank()) {
            systemContent += "\n\n以下是对论文分析结果的上下文：\n\n" + context;
        }
        if (!ragContext.isBlank()) {
            systemContent += ragContext;
        }
        return systemContent;
    }

    private String chatWithoutMemory(String context, String question, String ragContext) {
        String prompt = "以下是之前的分析上下文：\n\n" + (context != null ? context : "")
                + ragContext
                + "\n\n用户提问：" + question + "\n\n"
                + "请基于上下文回答用户的问题。如果上下文中没有相关信息，诚实说明。";
        return llmService.chat(
                "你是一位学术研究助手，帮助用户深入理解论文分析结果。回答简洁专业。",
                prompt);
    }
}
