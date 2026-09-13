package com.research.assistant.service.agent.source;

import com.research.assistant.entity.Paper;
import com.research.assistant.mapper.PaperMapper;
import com.research.assistant.service.pdf.layout.EvidenceLocator;
import com.research.assistant.service.pdf.layout.NormalizedBoundingBox;
import com.research.assistant.service.pdf.layout.PaperPdfFileResolver;
import com.research.assistant.service.pdf.layout.PdfDocumentFingerprint;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Color;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PaperSourceVisualServiceTest {

    @Test
    void rendersAtMostTwoSourceLinkedCropsWithVisualSourcesFirst(@TempDir Path directory) throws Exception {
        Path pdfPath = directory.resolve("paper.pdf");
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.setNonStrokingColor(Color.BLACK);
                stream.addRect(80, 500, 300, 80);
                stream.fill();
            }
            document.save(pdfPath.toFile());
        }
        Paper paper = new Paper();
        paper.setId(9L);
        paper.setPdfPath("paper.pdf");
        PaperMapper mapper = mock(PaperMapper.class);
        when(mapper.selectById(9L)).thenReturn(paper);
        PaperSourceVisualService service = new PaperSourceVisualService(
                mapper, new PaperPdfFileResolver(directory.toString()));

        Map<String, SourceObject> objects = new LinkedHashMap<>();
        objects.put("text", source("text", SourceContentType.TEXT));
        objects.put("formula", source("formula", SourceContentType.FORMULA));
        objects.put("figure", source("figure", SourceContentType.FIGURE));
        Map<String, List<SourceLocator>> locators = new LinkedHashMap<>();
        locators.put("text", List.of(locator("text", .1)));
        locators.put("formula", List.of(locator("formula", .3)));
        locators.put("figure", List.of(new SourceLocator("loc-figure", "figure", 1, "PDF_NORMALIZED",
                List.of(new NormalizedBoundingBox(.1, .80, .7, .06)),
                List.of(new NormalizedBoundingBox(.1, .20, .7, .30)),
                "Figure 1. Caption", EvidenceLocator.Precision.VISUAL_REGION)));
        PaperSourceCatalog catalog = new PaperSourceCatalog(9, PdfDocumentFingerprint.sha256(pdfPath.toFile()),
                "parser", 1, objects, locators);

        var visuals = service.render(catalog, List.of("text", "formula", "figure"));

        assertThat(visuals).hasSize(2);
        assertThat(visuals).extracting(visual -> visual.sourceObjectId())
                .containsExactly("formula", "figure");
        assertThat(visuals).allSatisfy(visual -> {
            assertThat(visual.bytes()).isNotEmpty();
            assertThat(visual.width()).isLessThanOrEqualTo(PaperSourceVisualService.MAX_WIDTH);
            assertThat(visual.height()).isLessThanOrEqualTo(PaperSourceVisualService.MAX_HEIGHT);
        });
        assertThat(visuals.stream().filter(visual -> visual.sourceObjectId().equals("figure"))
                .findFirst().orElseThrow().height()).isGreaterThan(700);
    }

    private SourceObject source(String id, SourceContentType type) {
        return new SourceObject(id, 9, "h".repeat(64), "parser", 1,
                type, id, id, List.of(), "", Map.of());
    }

    private SourceLocator locator(String id, double y) {
        return new SourceLocator("loc-" + id, id, 1, "PDF_NORMALIZED",
                List.of(new NormalizedBoundingBox(.1, y, .7, .12)), id,
                EvidenceLocator.Precision.BLOCK);
    }
}
