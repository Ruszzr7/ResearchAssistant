package com.research.assistant.service.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.entity.Paper;
import com.research.assistant.service.pdf.layout.DocumentBlock;
import com.research.assistant.service.pdf.layout.DocumentBlockContentMode;
import com.research.assistant.service.pdf.layout.DocumentBlockRole;
import com.research.assistant.service.pdf.layout.NormalizedBoundingBox;
import com.research.assistant.service.pdf.layout.PaperLayoutArtifact;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PaperStructureBuilderTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final PaperStructureBuilder builder = new PaperStructureBuilder(objectMapper);

    @Test
    void shouldBuildHierarchicalAddressableStructureWithoutModelCalls() throws Exception {
        Paper paper = new Paper();
        paper.setId(7L);
        paper.setTitle("A Structured Paper");
        paper.setAuthors("[{\"name\":\"Alice\"},{\"name\":\"Bob\"}]");
        paper.setKeywords("RSMA, finite blocklength; URLLC");
        paper.setYear(2026);

        PaperLayoutArtifact artifact = new PaperLayoutArtifact(
                7L,
                "a".repeat(64),
                "pdfbox-layout-v1+semantic-v2",
                0.91,
                Instant.parse("2026-07-19T00:00:00Z"),
                3,
                List.of(
                        block("title", 1, DocumentBlockRole.TITLE, 0,
                                "A Structured Paper", null, null, DocumentBlockContentMode.TEXT),
                        block("abstract", 1, DocumentBlockRole.ABSTRACT, 1,
                                "Abstract — We study a system.", null, null, DocumentBlockContentMode.TEXT),
                        block("intro-heading", 1, DocumentBlockRole.HEADING, 2,
                                "I. INTRODUCTION", null, null, DocumentBlockContentMode.TEXT),
                        block("intro-p1", 1, DocumentBlockRole.BODY, 3,
                                "This paragraph continues", null, null, DocumentBlockContentMode.TEXT),
                        block("intro-p2", 2, DocumentBlockRole.BODY, 4,
                                "across the page boundary.", null, null, DocumentBlockContentMode.TEXT),
                        block("method-heading", 2, DocumentBlockRole.HEADING, 5,
                                "II. METHOD", null, null, DocumentBlockContentMode.TEXT),
                        block("model-heading", 2, DocumentBlockRole.HEADING, 6,
                                "A. System Model", null, null, DocumentBlockContentMode.TEXT),
                        block("formula", 2, DocumentBlockRole.FORMULA, 7,
                                "R=(1+x)", "R=\\log_2(1+x)", null,
                                DocumentBlockContentMode.STRUCTURED),
                        block("figure", 2, DocumentBlockRole.FIGURE, 8,
                                "", null, null, DocumentBlockContentMode.REGION),
                        block("caption", 2, DocumentBlockRole.CAPTION, 9,
                                "Fig. 1. System overview.", null, null, DocumentBlockContentMode.TEXT),
                        block("references-heading", 3, DocumentBlockRole.HEADING, 10,
                                "REFERENCES", null, null, DocumentBlockContentMode.TEXT),
                        block("reference", 3, DocumentBlockRole.REFERENCE, 11,
                                "[1] A cited work.", null, null, DocumentBlockContentMode.TEXT),
                        block("footer", 3, DocumentBlockRole.FOOTER, 12,
                                "Authorized use only", null, null, DocumentBlockContentMode.TEXT)
                ));

        PaperStructure structure = builder.build(paper, artifact);

        assertThat(structure.schemaVersion()).isEqualTo(PaperStructure.SCHEMA_VERSION);
        assertThat(structure.metadata().authors()).containsExactly("Alice", "Bob");
        assertThat(structure.metadata().keywords())
                .containsExactly("RSMA", "finite blocklength", "URLLC");
        assertThat(structure.readingOrder()).contains("title", "formula", "reference")
                .doesNotContain("footer");
        assertThat(structure.sections()).extracting(PaperStructure.Section::kind)
                .contains("ABSTRACT", "SECTION", "REFERENCES");
        PaperStructure.Section method = structure.sections().stream()
                .filter(section -> "II. METHOD".equals(section.heading())).findFirst().orElseThrow();
        PaperStructure.Section model = structure.sections().stream()
                .filter(section -> "A. System Model".equals(section.heading())).findFirst().orElseThrow();
        assertThat(model.parentId()).isEqualTo(method.id());
        assertThat(model.path()).containsExactly("II. METHOD", "A. System Model");
        assertThat(structure.crossPageContinuations()).singleElement().satisfies(link -> {
            assertThat(link.fromBlockId()).isEqualTo("intro-p1");
            assertThat(link.toBlockId()).isEqualTo("intro-p2");
            assertThat(link.reason()).isEqualTo("OPEN_SENTENCE_PAGE_BREAK");
        });
        PaperStructure.Element caption = structure.elements().stream()
                .filter(element -> "CAPTION".equals(element.type())).findFirst().orElseThrow();
        assertThat(caption.label()).isEqualTo("Figure 1.");
        assertThat(caption.relatedBlockIds()).containsExactly("figure");
        assertThat(structure.quality().regionOnlyElements()).isEqualTo(1);
        assertThat(structure.statistics().excludedBlocks()).isEqualTo(1);

        String json = objectMapper.writeValueAsString(structure);
        PaperStructure restored = objectMapper.readValue(json, PaperStructure.class);
        assertThat(restored.sections()).isEqualTo(structure.sections());
        assertThat(restored.source().documentHash()).isEqualTo("a".repeat(64));
    }

    private DocumentBlock block(String id,
                                int page,
                                DocumentBlockRole role,
                                int order,
                                String text,
                                String latex,
                                String tableText,
                                DocumentBlockContentMode mode) {
        return new DocumentBlock(
                id,
                page,
                new NormalizedBoundingBox(0.1, 0.1 + order * 0.02, 0.7, 0.018),
                role,
                order,
                List.of(),
                text,
                latex,
                tableText,
                0.9,
                mode);
    }
}
