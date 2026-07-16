package com.research.assistant.service.translation;

import com.research.assistant.dto.translation.TranslationRequest;
import com.research.assistant.service.SettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefaultTranslationServiceTest {

    private SettingsService settingsService;
    private RecordingProvider provider;
    private DefaultTranslationService service;

    @BeforeEach
    void setUp() {
        settingsService = mock(SettingsService.class);
        when(settingsService.getValue("translation_provider")).thenReturn("deepl");
        provider = new RecordingProvider();
        service = new DefaultTranslationService(settingsService, List.of(provider),
                "deepl", "academic-v1", 100, Duration.ofHours(1));
    }

    @Test
    void protectsLatexCitationsDoiAndAbbreviationsAndCachesTheResult() {
        String source = "The rate $R_k$ in RSMA follows [12], see 10.1109/TWC.2025.1.";

        var first = service.translate(new TranslationRequest(List.of(source), "EN", "ZH"));
        var second = service.translate(new TranslationRequest(List.of(source), "EN", "ZH"));

        assertThat(first.items()).singleElement().satisfies(item -> {
            assertThat(item.text()).contains("该速率", "$R_k$", "RSMA", "[12]", "10.1109/TWC.2025.1");
            assertThat(item.detectedSourceLanguage()).isEqualTo("EN");
            assertThat(item.cached()).isFalse();
        });
        assertThat(second.cached()).isTrue();
        assertThat(second.items().get(0).cached()).isTrue();
        assertThat(provider.calls).isEqualTo(1);
        assertThat(provider.seenTexts.get(0)).contains(
                "<keep>$R_k$</keep>", "<keep>RSMA</keep>", "<keep>[12]</keep>");
    }

    @Test
    void splitsLongTextWithoutChangingItsOrder() {
        provider.translateVisibleText = false;
        String source = "A paragraph. ".repeat(900);

        var response = service.translate(new TranslationRequest(List.of(source), "EN", "ZH"));

        assertThat(response.items().get(0).text()).isEqualTo(source);
        assertThat(provider.seenTexts.size()).isGreaterThan(1);
        assertThat(provider.calls).isEqualTo(1);
    }

    @Test
    void reportsMissingProviderConfigurationWithoutFallingBackToTheLlm() {
        provider.configured = false;

        assertThatThrownBy(() -> service.translate(
                new TranslationRequest(List.of("text"), "EN", "ZH")))
                .isInstanceOf(TranslationException.class)
                .satisfies(error -> assertThat(((TranslationException) error).code())
                        .isEqualTo("PROVIDER_NOT_CONFIGURED"));
    }

    private static final class RecordingProvider implements TranslationProvider {
        private int calls;
        private boolean configured = true;
        private boolean translateVisibleText = true;
        private final List<String> seenTexts = new ArrayList<>();

        @Override
        public String id() {
            return "deepl";
        }

        @Override
        public boolean configured() {
            return configured;
        }

        @Override
        public List<ProviderTranslation> translate(List<String> texts,
                                                   TranslationLanguage sourceLanguage,
                                                   TranslationLanguage targetLanguage) {
            calls++;
            seenTexts.addAll(texts);
            return texts.stream().map(text -> new ProviderTranslation(
                    translateVisibleText ? text.replace("The rate", "该速率") : text, "EN"))
                    .toList();
        }
    }
}
