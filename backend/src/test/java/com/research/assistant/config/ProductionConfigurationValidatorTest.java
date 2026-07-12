package com.research.assistant.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductionConfigurationValidatorTest {

    @Test
    void acceptsExplicitProductionConfiguration() {
        ProductionConfigurationValidator validator = new ProductionConfigurationValidator(
                "0123456789abcdef",
                true,
                "jdbc:mysql://mysql:3306/research_assistant",
                "/data/papers",
                "http://localhost:8088");

        assertThatCode(validator::afterPropertiesSet).doesNotThrowAnyException();
    }

    @Test
    void rejectsMissingOrUnsafeProductionSettings() {
        assertThatThrownBy(() -> new ProductionConfigurationValidator(
                "short", true, "jdbc:mysql://mysql/db", "/data/papers", "http://localhost:8088")
                .afterPropertiesSet()).isInstanceOf(IllegalStateException.class);

        assertThatThrownBy(() -> new ProductionConfigurationValidator(
                "0123456789abcdef", true, "jdbc:h2:mem:test", "/data/papers", "http://localhost:8088")
                .afterPropertiesSet()).isInstanceOf(IllegalStateException.class);

        assertThatThrownBy(() -> new ProductionConfigurationValidator(
                "0123456789abcdef", true, "jdbc:mysql://mysql/db", "/data/papers", "*")
                .afterPropertiesSet()).isInstanceOf(IllegalStateException.class);
    }
}
