package com.research.assistant.service.memory;

import com.research.assistant.service.ai.LlmCallPolicy;
import com.research.assistant.service.pdf.layout.PaperLayoutArtifactService;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

/** Disposable probe that separates provider latency from multimodal paper payload cost. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.task.scheduling.enabled=false", "app.async.dispatch-enabled=false",
        "app.async.mark-orphaned-on-startup=false"
})
@EnabledIfEnvironmentVariable(named = "RUN_DOCUMENT_MODEL_LATENCY_EVAL", matches = "true")
class PaperDocumentModelLatencyRealEvalTest {

    @Autowired private PaperMemoryModelClient modelClient;
    @Autowired private PaperMemoryService memoryService;
    @Autowired private PaperLayoutArtifactService artifactService;
    @Autowired private PaperWholeDocumentInputBuilder inputBuilder;

    @Test
    void answersASmallJsonRequest() {
        long started = System.nanoTime();
        var response = modelClient.chat("Return only JSON.",
                List.of(TextContent.from("Return {\"status\":\"ok\"}.")),
                new LlmCallPolicy("document-latency-probe", 1_000, 200, 100, 1, true, "low"));
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        System.out.printf("DOCUMENT_MODEL_LATENCY elapsedMs=%d finish=%s promptTokens=%s completionTokens=%s content=%s%n",
                elapsedMs, response.getFinishReason(), response.getPromptTokens(),
                response.getCompletionTokens(), response.getContent());
        assertThat(response.getContent()).contains("ok");
    }

    @Test
    void answersWithActualPaperImages() {
        String configured = System.getenv("DOCUMENT_IMAGE_PROBE_COUNT");
        if (configured == null || configured.isBlank()) return;
        int imageCount = Integer.parseInt(configured);
        long paperId = Long.parseLong(System.getenv().getOrDefault("DOCUMENT_IMAGE_PROBE_PAPER_ID", "185"));
        PaperMemoryState state = memoryService.ensureStructure(paperId, false);
        var artifact = artifactService.ensureArtifact(paperId, false);
        var input = inputBuilder.build(state.structure(), artifact, "Return JSON.");
        List<Content> contents = new ArrayList<>();
        contents.add(TextContent.from("Inspect the attached images and return {\"status\":\"ok\"}."));
        input.contents().stream().filter(ImageContent.class::isInstance).limit(imageCount)
                .forEach(contents::add);
        long started = System.nanoTime();
        var response = modelClient.chat("Return only JSON.", contents,
                new LlmCallPolicy("document-image-latency-probe", 1_000, 200, 500, 1, true, "low"));
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        System.out.printf("DOCUMENT_IMAGE_LATENCY paper=%d images=%d elapsedMs=%d finish=%s content=%s%n",
                paperId, imageCount, elapsedMs, response.getFinishReason(), response.getContent());
        assertThat(response.getContent()).contains("ok");
    }

    @Test
    void answersWithActualPaperText() {
        String configured = System.getenv("DOCUMENT_IMAGE_PROBE_COUNT");
        if (configured == null || configured.isBlank()) return;
        long paperId = Long.parseLong(System.getenv().getOrDefault("DOCUMENT_IMAGE_PROBE_PAPER_ID", "185"));
        PaperMemoryState state = memoryService.ensureStructure(paperId, false);
        var artifact = artifactService.ensureArtifact(paperId, false);
        var input = inputBuilder.build(state.structure(), artifact, "Return JSON.");
        List<Content> contents = new ArrayList<>();
        contents.add(TextContent.from("Read the attached structured paper text and return {\"status\":\"ok\"}."));
        input.contents().stream()
                .filter(TextContent.class::isInstance)
                .skip(1)
                .forEach(contents::add);
        measure("DOCUMENT_TEXT_LATENCY", paperId, contents);
    }

    @Test
    void answersWithActualPaperTextAndPageImages() {
        String configured = System.getenv("DOCUMENT_IMAGE_PROBE_COUNT");
        if (configured == null || configured.isBlank()) return;
        long paperId = Long.parseLong(System.getenv().getOrDefault("DOCUMENT_IMAGE_PROBE_PAPER_ID", "185"));
        PaperMemoryState state = memoryService.ensureStructure(paperId, false);
        var artifact = artifactService.ensureArtifact(paperId, false);
        var input = inputBuilder.build(state.structure(), artifact, "Return JSON.");
        List<Content> contents = new ArrayList<>();
        contents.add(TextContent.from("Read the attached structured paper text and page images and return {\"status\":\"ok\"}."));
        input.contents().stream()
                .filter(TextContent.class::isInstance)
                .skip(1)
                .forEach(contents::add);
        input.contents().stream()
                .filter(ImageContent.class::isInstance)
                .limit(input.imageCount())
                .forEach(contents::add);
        measure("DOCUMENT_TEXT_PAGE_IMAGES_LATENCY", paperId, contents);
    }

    private void measure(String label, long paperId, List<Content> contents) {
        int maxOutputTokens = Integer.parseInt(System.getenv().getOrDefault(
                "DOCUMENT_PROBE_MAX_OUTPUT", "500"));
        long started = System.nanoTime();
        var response = modelClient.chat("Return only JSON.", contents,
                new LlmCallPolicy("document-payload-latency-probe", 400_000, 120_000,
                        maxOutputTokens, 1, true, "low"));
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        System.out.printf("%s paper=%d textParts=%d imageParts=%d elapsedMs=%d finish=%s promptTokens=%s completionTokens=%s content=%s%n",
                label, paperId,
                contents.stream().filter(TextContent.class::isInstance).count(),
                contents.stream().filter(ImageContent.class::isInstance).count(),
                elapsedMs, response.getFinishReason(), response.getPromptTokens(),
                response.getCompletionTokens(), response.getContent());
        assertThat(response).isNotNull();
    }
}
