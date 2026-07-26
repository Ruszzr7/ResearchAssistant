package com.research.assistant.service.pdf.formula.region;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Low-cardinality formula-recognition telemetry. It deliberately records no
 * paper identifiers, coordinates, image contents, LaTeX, prompts or credentials.
 */
@Component
public class FormulaRecognitionTelemetry {

    private static final Logger log = LoggerFactory.getLogger(FormulaRecognitionTelemetry.class);
    private final MeterRegistry registry;

    public FormulaRecognitionTelemetry(MeterRegistry registry) {
        this.registry = registry;
    }

    public long start() {
        return System.nanoTime();
    }

    public void stage(String stage, String outcome, long startedAtNanos) {
        long duration = elapsed(startedAtNanos);
        Timer.builder("research.formula.recognition.stage.duration")
                .tags("stage", safe(stage), "outcome", safe(outcome))
                .register(registry)
                .record(Duration.ofNanos(duration));
        log.debug("event=formula_recognition_stage stage={} outcome={} durationMs={}",
                safe(stage), safe(outcome), duration / 1_000_000);
    }

    public void completed(String path, String outcome, long startedAtNanos) {
        long duration = elapsed(startedAtNanos);
        Timer.builder("research.formula.recognition.duration")
                .tags("path", safe(path), "outcome", safe(outcome))
                .register(registry)
                .record(Duration.ofNanos(duration));
        log.info("event=formula_recognition_completed path={} outcome={} durationMs={}",
                safe(path), safe(outcome), duration / 1_000_000);
    }

    public void image(int width, int height, int byteCount) {
        DistributionSummary.builder("research.formula.recognition.image.pixels")
                .register(registry)
                .record(Math.max(0L, (long) width * height));
        DistributionSummary.builder("research.formula.recognition.image.bytes")
                .register(registry)
                .record(Math.max(0, byteCount));
        log.debug("event=formula_recognition_image width={} height={} bytes={}",
                Math.max(0, width), Math.max(0, height), Math.max(0, byteCount));
    }

    private long elapsed(long startedAtNanos) {
        return Math.max(0, System.nanoTime() - startedAtNanos);
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }
}
