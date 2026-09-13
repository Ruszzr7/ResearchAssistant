package com.research.assistant.service.pdf.layout;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.service.agent.source.PaperSourceCatalog;
import com.research.assistant.service.agent.source.PaperSourceCatalogService;
import com.research.assistant.service.agent.source.SourceContentType;
import com.research.assistant.service.agent.source.SourceObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.InputStream;
import java.sql.DriverManager;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Read-only regression for the real figure-caption failures found in papers 191 and 207. */
@EnabledIfEnvironmentVariable(named = "RUN_FIGURE_CAPTION_REAL_EVAL", matches = "true")
class PaperFigureCaptionRealEvalTest {

    private static final String CASES = "/eval/figure-caption-real-cases.json";
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final PaperSourceIndexService indexService = new PaperSourceIndexService();

    @Test
    void separatesConfirmedCaptionsFromFigureDiscussionParagraphs() throws Exception {
        Suite suite;
        try (InputStream input = getClass().getResourceAsStream(CASES)) {
            assertThat(input).isNotNull();
            suite = objectMapper.readValue(input, Suite.class);
        }
        try (var connection = DriverManager.getConnection(
                env("SPRING_DATASOURCE_URL",
                        "jdbc:mysql://localhost:3306/research_assistant?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=UTF-8"),
                env("SPRING_DATASOURCE_USERNAME", "root"),
                env("SPRING_DATASOURCE_PASSWORD", ""));
             var statement = connection.prepareStatement("""
                     SELECT document_hash, parser_version, layout_confidence, page_count, blocks_json
                     FROM paper_layout_artifact
                     WHERE paper_id = ? AND status = 'READY'
                     ORDER BY generated_at DESC, id DESC LIMIT 1
                     """)) {
            for (PaperCase paperCase : suite.papers()) {
                statement.setLong(1, paperCase.paperId());
                try (var rows = statement.executeQuery()) {
                    assertThat(rows.next()).as("paper %s has a layout artifact", paperCase.paperId()).isTrue();
                    List<DocumentBlock> blocks = objectMapper.readValue(
                            rows.getString("blocks_json"), new TypeReference<>() { });
                    PaperLayoutArtifact artifact = new PaperLayoutArtifact(
                            paperCase.paperId(), rows.getString("document_hash"),
                            rows.getString("parser_version"), rows.getDouble("layout_confidence"),
                            null, rows.getInt("page_count"), blocks);
                    List<PaperSourceUnit> figures = indexService.build(artifact).sourceUnits().stream()
                            .filter(unit -> unit.kind() == PaperSourceUnit.Kind.FIGURE).toList();
                    Set<String> figureBlockIds = figures.stream().flatMap(unit -> unit.blocks().stream())
                            .map(DocumentBlock::id).collect(Collectors.toSet());

                    assertThat(figureBlockIds).containsAll(paperCase.acceptedCaptionBlockIds());
                    assertThat(figureBlockIds).doesNotContainAnyElementsOf(
                            paperCase.rejectedDiscussionBlockIds());

                    PaperSourceCatalog catalog = new PaperSourceCatalogService(
                            mock(PaperLayoutArtifactService.class), indexService).build(artifact);
                    for (String captionBlockId : paperCase.acceptedCaptionBlockIds()) {
                        SourceObject figure = sourceContaining(catalog, captionBlockId);
                        assertThat(figure.contentType()).isEqualTo(SourceContentType.FIGURE);
                        assertThat(figure.provenance()).containsKeys("figureNumber", "captionOf");
                        System.out.printf("FIGURE_EVAL paper=%d captionBlock=%s source=%s number=%s text=%s%n",
                                paperCase.paperId(), captionBlockId, figure.sourceObjectId(),
                                figure.provenance().get("figureNumber"), figure.rawContent());
                    }
                    for (String discussionBlockId : paperCase.rejectedDiscussionBlockIds()) {
                        SourceObject discussion = sourceContaining(catalog, discussionBlockId);
                        assertThat(discussion.contentType()).isEqualTo(SourceContentType.TEXT);
                        String figureId = discussion.provenance().get("discussesFigure");
                        assertThat(figureId).isNotBlank();
                        SourceObject linkedFigure = catalog.requireObject(figureId);
                        assertThat(linkedFigure.contentType()).isEqualTo(SourceContentType.FIGURE);
                        assertThat(linkedFigure.provenance().getOrDefault("discussedBy", ""))
                                .contains(discussion.sourceObjectId());
                        System.out.printf("FIGURE_EVAL paper=%d discussionBlock=%s source=%s links=%s%n",
                                paperCase.paperId(), discussionBlockId, discussion.sourceObjectId(), figureId);
                    }
                }
            }
        }
    }

    private SourceObject sourceContaining(PaperSourceCatalog catalog, String blockId) {
        return catalog.objects().values().stream()
                .filter(source -> List.of(source.provenance().getOrDefault("blockIds", "").split(","))
                        .contains(blockId))
                .findFirst().orElseThrow(() -> new AssertionError(
                        "no source contains block " + blockId));
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private record Suite(List<PaperCase> papers) { }

    private record PaperCase(long paperId,
                             List<String> rejectedDiscussionBlockIds,
                             List<String> acceptedCaptionBlockIds) { }
}
