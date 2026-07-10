package com.research.assistant.service.pdf;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link FigureRegionType} 单元测试。
 */
class FigureRegionTypeTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldDefaultToFigureForBlankOrNull() {
        assertThat(FigureRegionType.from(null)).isEqualTo(FigureRegionType.FIGURE);
        assertThat(FigureRegionType.from("")).isEqualTo(FigureRegionType.FIGURE);
        assertThat(FigureRegionType.from("  ")).isEqualTo(FigureRegionType.FIGURE);
    }

    @Test
    void shouldParseCaseInsensitively() {
        assertThat(FigureRegionType.from("table")).isEqualTo(FigureRegionType.TABLE);
        assertThat(FigureRegionType.from("TABLE")).isEqualTo(FigureRegionType.TABLE);
        assertThat(FigureRegionType.from("Fig")).isEqualTo(FigureRegionType.FIGURE);
        assertThat(FigureRegionType.from("IMAGE")).isEqualTo(FigureRegionType.FIGURE);
    }

    @Test
    void shouldSerializeToUppercaseName() throws Exception {
        String json = objectMapper.writeValueAsString(FigureRegionType.TABLE);
        assertThat(json).isEqualTo("\"TABLE\"");
    }

    @Test
    void shouldDeserializeFromLowercaseString() throws Exception {
        FigureRegionType type = objectMapper.readValue("\"table\"", FigureRegionType.class);
        assertThat(type).isEqualTo(FigureRegionType.TABLE);
    }
}
