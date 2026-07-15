package com.research.assistant.service.pdf.layout;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExternalLayoutPayloadNormalizerTest {

    private final ExternalLayoutPayloadNormalizer normalizer =
            new ExternalLayoutPayloadNormalizer(new ObjectMapper());

    @Test
    void shouldNormalizeMineruCoordinatesAndStructuredRegions() {
        String payload = """
                {
                  "pdf_info": [{
                    "page_idx": 0,
                    "page_size": [1000, 2000],
                    "para_blocks": [
                      {"type":"text","bbox":[100,200,900,300],
                       "lines":[{"spans":[{"content":"A reliable paragraph."}]}]},
                      {"type":"interline_equation","bbox":[200,400,800,520],
                       "latex":"x = y + 1"},
                      {"type":"table","bbox":[120,600,880,1000],"text":"TABLE I"}
                    ]
                  }]
                }
                """;

        PaperLayoutArtifact artifact = normalizer.normalize(
                "MINERU", payload, 7L, "b".repeat(64));

        assertThat(artifact.pageCount()).isEqualTo(1);
        assertThat(artifact.blocks()).hasSize(3);
        assertThat(artifact.blocks().get(0).bbox())
                .isEqualTo(new NormalizedBoundingBox(0.1, 0.1, 0.8, 0.05));
        assertThat(artifact.blocks()).anySatisfy(block -> {
            assertThat(block.role()).isEqualTo(DocumentBlockRole.FORMULA);
            assertThat(block.contentMode()).isEqualTo(DocumentBlockContentMode.STRUCTURED);
            assertThat(block.latex()).isEqualTo("x = y + 1");
        });
        assertThat(artifact.blocks()).anySatisfy(block -> {
            assertThat(block.role()).isEqualTo(DocumentBlockRole.TABLE);
            assertThat(block.contentMode()).isEqualTo(DocumentBlockContentMode.REGION);
        });
    }

    @Test
    void shouldNormalizeCoordinateBearingGrobidTei() {
        String payload = """
                <TEI xmlns="http://www.tei-c.org/ns/1.0">
                  <facsimile><surface n="1" ulx="0" uly="0" lrx="600" lry="800"/></facsimile>
                  <text><body>
                    <head coords="1,60,80,300,25">I. Method</head>
                    <p coords="1,60,120,250,40">Grounded body evidence.</p>
                    <formula coords="1,100,180,200,50">x = y + 1</formula>
                  </body></text>
                </TEI>
                """;

        PaperLayoutArtifact artifact = normalizer.normalize(
                "GROBID", payload, 9L, "c".repeat(64));

        assertThat(artifact.blocks()).hasSize(3);
        assertThat(artifact.blocks()).extracting(DocumentBlock::role)
                .containsExactly(DocumentBlockRole.HEADING, DocumentBlockRole.BODY,
                        DocumentBlockRole.FORMULA);
        assertThat(artifact.blocks().get(2).contentMode())
                .isEqualTo(DocumentBlockContentMode.STRUCTURED);
    }

    @Test
    void shouldRejectXmlWithDoctype() {
        String payload = """
                <!DOCTYPE foo [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <TEI><text><body><p coords="1,1,1,10,10">&xxe;</p></body></text></TEI>
                """;

        assertThatThrownBy(() -> normalizer.normalize(
                "GROBID", payload, 1L, "d".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
