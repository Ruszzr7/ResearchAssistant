package com.research.assistant.service.ai;

import com.research.assistant.entity.Conversation;
import com.research.assistant.mapper.ConversationMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * {@link JdbcChatMemoryStore} 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class JdbcChatMemoryStoreTest {

    @Mock
    private ConversationMapper conversationMapper;

    private JdbcChatMemoryStore store;

    @BeforeEach
    void setUp() {
        store = new JdbcChatMemoryStore(conversationMapper);
    }

    @Test
    void shouldReturnMessagesInOrder() {
        Conversation row1 = new Conversation("mem-1", "SYSTEM", "sys");
        Conversation row2 = new Conversation("mem-1", "USER", "hello");
        Conversation row3 = new Conversation("mem-1", "AI", "hi");
        when(conversationMapper.selectByMemoryId("mem-1")).thenReturn(List.of(row1, row2, row3));

        List<ChatMessage> messages = store.getMessages("mem-1");

        assertThat(messages).hasSize(3);
        assertThat(messages.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(((SystemMessage) messages.get(0)).text()).isEqualTo("sys");
        assertThat(messages.get(1)).isInstanceOf(UserMessage.class);
        assertThat(messages.get(2)).isInstanceOf(AiMessage.class);
    }

    @Test
    void shouldDeleteThenInsertOnUpdate() {
        List<ChatMessage> messages = List.of(
                SystemMessage.from("sys"),
                UserMessage.from("question"),
                AiMessage.from("answer")
        );

        store.updateMessages("mem-2", messages);

        verify(conversationMapper).deleteByMemoryId("mem-2");
        ArgumentCaptor<Conversation> captor = ArgumentCaptor.forClass(Conversation.class);
        verify(conversationMapper, times(3)).insert(captor.capture());

        List<Conversation> saved = captor.getAllValues();
        assertThat(saved).extracting(Conversation::getRole).containsExactly("SYSTEM", "USER", "AI");
        assertThat(saved).extracting(Conversation::getContent).containsExactly("sys", "question", "answer");
        assertThat(saved).extracting(Conversation::getMemoryId).containsOnly("mem-2");
    }

    @Test
    void shouldDeleteMessages() {
        store.deleteMessages("mem-3");
        verify(conversationMapper).deleteByMemoryId("mem-3");
    }

    @Test
    void shouldIgnoreUnknownRoleOnRead() {
        when(conversationMapper.selectByMemoryId("mem-4"))
                .thenReturn(List.of(new Conversation("mem-4", "UNKNOWN", "x")));

        List<ChatMessage> messages = store.getMessages("mem-4");

        assertThat(messages).isEmpty();
    }
}
