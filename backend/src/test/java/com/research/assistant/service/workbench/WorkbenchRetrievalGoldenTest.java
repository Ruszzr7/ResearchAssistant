package com.research.assistant.service.workbench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.service.pdf.layout.DocumentBlock;
import com.research.assistant.service.pdf.layout.DocumentBlockContentMode;
import com.research.assistant.service.pdf.layout.DocumentBlockRole;
import com.research.assistant.service.pdf.layout.LayoutEvidence;
import com.research.assistant.service.pdf.layout.NormalizedBoundingBox;
import com.research.assistant.service.pdf.layout.PaperLayoutArtifact;
import com.research.assistant.service.pdf.layout.PaperLayoutEvidencePolicy;
import com.research.assistant.service.pdf.layout.PaperLayoutEvidenceService;
import com.research.assistant.service.pdf.layout.SelectionAnchorResolver;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class WorkbenchRetrievalGoldenTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void keepsKnownRetrievalFailuresFixed() throws Exception {
        PaperLayoutEvidencePolicy policy = new PaperLayoutEvidencePolicy();
        WorkbenchEvidenceRetrievalService service = new WorkbenchEvidenceRetrievalService(
                policy, new PaperLayoutEvidenceService(policy, mock(SelectionAnchorResolver.class)));
        try (InputStream stream = getClass().getResourceAsStream(
                "/eval/workbench-retrieval-golden.json")) {
            JsonNode root = objectMapper.readTree(stream);
            for (JsonNode testCase : root.path("cases")) {
                List<DocumentBlock> blocks = new ArrayList<>();
                for (JsonNode block : testCase.path("blocks")) blocks.add(block(block));
                PaperLayoutArtifact artifact = new PaperLayoutArtifact(
                        99L, "a".repeat(64), "golden-parser-v1", 0.9,
                        Instant.parse("2026-08-02T00:00:00Z"), 12, blocks);

                List<LayoutEvidence> result = service.retrievePaper(
                        artifact, testCase.path("query").asText(), 8, 8_000);
                String label = testCase.path("name").asText();
                assertThat(result).as(label).isNotEmpty();
                assertThat(result.get(0).blockId()).as(label + " top hit")
                        .isEqualTo(testCase.path("topBlockId").asText());
                assertThat(result).extracting(LayoutEvidence::blockId).as(label + " included")
                        .containsAll(strings(testCase.path("includedBlockIds")));
                assertThat(result).extracting(LayoutEvidence::blockId).as(label + " excluded")
                        .doesNotContainAnyElementsOf(strings(testCase.path("excludedBlockIds")));
            }
        }
    }

    private DocumentBlock block(JsonNode value) {
        return new DocumentBlock(
                value.path("id").asText(), value.path("page").asInt(),
                new NormalizedBoundingBox(value.path("x").asDouble(), value.path("y").asDouble(),
                        value.path("width").asDouble(), value.path("height").asDouble()),
                DocumentBlockRole.valueOf(value.path("role").asText()),
                value.path("order").asInt(), strings(value.path("section")),
                value.path("text").asText(), null, null, 0.9,
                DocumentBlockContentMode.valueOf(value.path("mode").asText()));
    }

    private List<String> strings(JsonNode values) {
        List<String> result = new ArrayList<>();
        values.forEach(value -> result.add(value.asText()));
        return result;
    }
}
