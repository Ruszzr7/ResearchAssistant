package com.research.assistant.service.pdf.formula.region;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FormulaRecognitionTelemetryTest {

    @Test
    void recordsOnlyLowCardinalityDurationsAndImageSizes() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        FormulaRecognitionTelemetry telemetry = new FormulaRecognitionTelemetry(registry);

        telemetry.stage("page_render", "success", telemetry.start());
        telemetry.image(900, 180, 12_345);
        telemetry.completed("vision_model", "success", telemetry.start());

        assertThat(registry.get("research.formula.recognition.stage.duration")
                .tag("stage", "page_render").timer().count()).isEqualTo(1);
        assertThat(registry.get("research.formula.recognition.duration")
                .tag("path", "vision_model").timer().count()).isEqualTo(1);
        assertThat(registry.get("research.formula.recognition.image.pixels")
                .summary().totalAmount()).isEqualTo(162_000);
        assertThat(registry.get("research.formula.recognition.image.bytes")
                .summary().totalAmount()).isEqualTo(12_345);
    }
}
