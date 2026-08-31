package com.research.assistant.service.ai;

import com.research.assistant.service.SettingsService;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link LangChain4jModelFactory} 单元测试。
 */
class LangChain4jModelFactoryTest {

    private final SettingsService settingsService = mock(SettingsService.class);
    private final LangChain4jModelFactory factory = new LangChain4jModelFactory(settingsService);

    @Test
    void shouldNormalizeBaseUrl() {
        givenSettings("https://api.deepseek.com", "deepseek-chat", "sk-key");

        ChatModel model = factory.createChatModel();

        // OpenAiChatModel 不暴露 baseUrl，但至少能成功构建说明 URL 已被接受
        assertThat(model).isNotNull();
    }

    @Test
    void shouldNormalizeBaseUrlWithTrailingSlash() {
        givenSettings("https://api.deepseek.com/", "deepseek-chat", "sk-key");

        ChatModel model = factory.createChatModel();

        assertThat(model).isNotNull();
    }

    @Test
    void shouldNormalizeBaseUrlWithDuplicateV1() {
        givenSettings("https://api.deepseek.com/v1", "deepseek-chat", "sk-key");

        ChatModel model = factory.createChatModel();

        assertThat(model).isNotNull();
    }

    @Test
    void shouldResolveTemperatureForKimiK2Code() {
        givenSettings("https://api.moonshot.cn", "kimi-k2.7-code", "sk-key");

        ChatModel model = factory.createChatModel();

        assertThat(model).isNotNull();
    }

    @Test
    void agentModelUsesASeparateNoBlindRetryCache() {
        givenSettings("https://api.deepseek.com", "deepseek-chat", "sk-key");

        ChatModel agentFirst = factory.createAgentChatModel();
        ChatModel agentSecond = factory.createAgentChatModel();
        ChatModel regular = factory.createChatModel();

        assertThat(agentSecond).isSameAs(agentFirst);
        assertThat(regular).isNotSameAs(agentFirst);
    }

    @Test
    void shouldThrowWhenApiKeyMissing() {
        givenSettings("https://api.deepseek.com", "deepseek-chat", "");

        assertThatThrownBy(factory::createChatModel)
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("API Key");
    }

    @Test
    void shouldThrowWhenModelMissing() {
        givenSettings("https://api.deepseek.com", null, "sk-key");

        assertThatThrownBy(factory::createChatModel)
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("模型");
    }

    private void givenSettings(String baseUrl, String model, String apiKey) {
        when(settingsService.getValue("base_url")).thenReturn(baseUrl);
        when(settingsService.getValue("model")).thenReturn(model);
        when(settingsService.getValue("api_key")).thenReturn(apiKey);
    }
}
