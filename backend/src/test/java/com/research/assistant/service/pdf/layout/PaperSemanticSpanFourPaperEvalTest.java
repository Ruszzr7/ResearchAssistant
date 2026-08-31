package com.research.assistant.service.pdf.layout;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.DriverManager;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Read-only local regression over the four papers used during the refactor. */
@EnabledIfEnvironmentVariable(named = "RUN_PAPER_SPAN_REAL_EVAL", matches = "true")
class PaperSemanticSpanFourPaperEvalTest {

    private static final List<Long> PAPER_IDS = List.of(184L, 185L, 190L, 191L);
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final PaperSemanticSpanBuilder builder = new PaperSemanticSpanBuilder();

    @Test
    void evaluatesCurrentFourLayoutArtifactsWithoutCallingAModel() throws Exception {
        String url = env("SPRING_DATASOURCE_URL",
                "jdbc:mysql://localhost:3306/research_assistant?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=UTF-8");
        try (var connection = DriverManager.getConnection(url,
                env("SPRING_DATASOURCE_USERNAME", "root"), env("SPRING_DATASOURCE_PASSWORD", ""));
             var statement = connection.prepareStatement("""
                     SELECT document_hash, parser_version, layout_confidence, page_count, blocks_json
                     FROM paper_layout_artifact WHERE paper_id = ? ORDER BY id DESC LIMIT 1
                     """)) {
            for (Long paperId : PAPER_IDS) {
                statement.setLong(1, paperId);
                try (var rows = statement.executeQuery()) {
                    assertThat(rows.next()).as("paper %s has a layout artifact", paperId).isTrue();
                    List<DocumentBlock> blocks = objectMapper.readValue(
                            rows.getString("blocks_json"), new TypeReference<>() { });
                    PaperLayoutArtifact artifact = new PaperLayoutArtifact(
                            paperId, rows.getString("document_hash"), rows.getString("parser_version"),
                            rows.getDouble("layout_confidence"), null, rows.getInt("page_count"), blocks);
                    List<PaperSemanticSpan> spans = builder.build(artifact);

                    Set<String> mapped = new LinkedHashSet<>();
                    spans.forEach(span -> mapped.addAll(span.blockIds()));
                    long nonEmpty = blocks.stream().filter(block -> !sourceText(block).isBlank()).count();
                    long merged = spans.stream().filter(span -> span.blocks().size() > 1).count();
                    long corrected = spans.stream().flatMap(span -> span.blocks().stream()
                                    .map(block -> block.role() != builder.effectiveRole(block)))
                            .filter(Boolean::booleanValue).count();
                    long formulaToBody = blocks.stream()
                            .filter(block -> block.role() == DocumentBlockRole.FORMULA
                                    && builder.effectiveRole(block) == DocumentBlockRole.BODY)
                            .count();
                    int maxBlocks = spans.stream().mapToInt(span -> span.blocks().size()).max().orElse(0);
                    double reduction = nonEmpty == 0 ? 0 : 1.0 - (double) spans.size() / nonEmpty;

                    assertThat(spans).isNotEmpty();
                    assertThat(mapped).hasSize((int) nonEmpty);
                    assertThat(spans).allSatisfy(span -> {
                        assertThat(span.text()).isNotBlank();
                        assertThat(span.blocks()).allSatisfy(block -> {
                            assertThat(block.page()).isBetween(1, artifact.pageCount());
                            assertThat(block.bbox()).isNotNull();
                        });
                    });
                    System.out.printf(Locale.ROOT,
                            "SPAN_EVAL paper=%d raw=%d spans=%d reduction=%.3f merged=%d maxBlocks=%d correctedRoles=%d formulaToBody=%d%n",
                            paperId, nonEmpty, spans.size(), reduction, merged, maxBlocks, corrected, formulaToBody);
                    if (paperId == 184L) {
                        spans.stream()
                                .filter(span -> span.blockIds().contains("p4-b0048")
                                        || span.blockIds().contains("p4-b0050"))
                                .forEach(span -> System.out.printf(
                                        "SPAN_EVAL evidence184=%s role=%s blocks=%s text=%s%n",
                                        span.id(), span.role(), span.blockIds(), span.text()));
                    }
                    if (paperId == 191L) {
                        PaperSemanticSpan known = spans.stream()
                                .filter(span -> span.text().contains("91.48%"))
                                .findFirst().orElseThrow();
                        assertThat(known.blockIds()).contains("p9-b0112", "p9-b0115", "p9-b0116", "p9-b0120");
                        System.out.printf("SPAN_EVAL known191=%s blocks=%s text=%s%n",
                                known.id(), known.blockIds(), known.text());
                    }
                }
            }
        }
    }

    private static String sourceText(DocumentBlock block) {
        if (block.role() == DocumentBlockRole.TABLE
                && block.tableText() != null && !block.tableText().isBlank()) return block.tableText();
        if (block.role() == DocumentBlockRole.FORMULA
                && block.latex() != null && !block.latex().isBlank()) return block.latex();
        return block.text() == null ? "" : block.text();
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
