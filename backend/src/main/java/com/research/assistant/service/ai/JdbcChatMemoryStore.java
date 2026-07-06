package com.research.assistant.service.ai;

import com.research.assistant.entity.Conversation;
import com.research.assistant.mapper.ConversationMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 基于 MySQL 的 ChatMemory 存储实现。
 * <p>
 * 每个 memoryId 对应一条对话线程，所有消息按时间顺序存入 `conversation` 表。
 */
@Component
public class JdbcChatMemoryStore implements ChatMemoryStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcChatMemoryStore.class);

    private final ConversationMapper conversationMapper;

    public JdbcChatMemoryStore(ConversationMapper conversationMapper) {
        this.conversationMapper = conversationMapper;
    }

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        List<Conversation> rows = conversationMapper.selectByMemoryId(String.valueOf(memoryId));
        List<ChatMessage> messages = new ArrayList<>();
        for (Conversation row : rows) {
            ChatMessage msg = toChatMessage(row);
            if (msg != null) {
                messages.add(msg);
            }
        }
        return messages;
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        String id = String.valueOf(memoryId);
        conversationMapper.deleteByMemoryId(id);
        for (ChatMessage message : messages) {
            Conversation row = toConversation(id, message);
            if (row != null) {
                conversationMapper.insert(row);
            }
        }
        log.debug("ChatMemory 已持久化 memoryId={} messages={}", id, messages.size());
    }

    @Override
    public void deleteMessages(Object memoryId) {
        conversationMapper.deleteByMemoryId(String.valueOf(memoryId));
    }

    private ChatMessage toChatMessage(Conversation row) {
        return switch (row.getRole()) {
            case "SYSTEM" -> SystemMessage.from(row.getContent());
            case "USER" -> UserMessage.from(row.getContent());
            case "AI" -> AiMessage.from(row.getContent());
            default -> {
                log.warn("未知对话角色: {}", row.getRole());
                yield null;
            }
        };
    }

    private Conversation toConversation(String memoryId, ChatMessage message) {
        if (message instanceof SystemMessage sys) {
            return new Conversation(memoryId, "SYSTEM", sys.text());
        }
        if (message instanceof UserMessage user) {
            return new Conversation(memoryId, "USER", user.singleText());
        }
        if (message instanceof AiMessage ai) {
            return new Conversation(memoryId, "AI", ai.text());
        }
        log.warn("暂不支持持久化的消息类型: {}", message.getClass().getSimpleName());
        return null;
    }
}
