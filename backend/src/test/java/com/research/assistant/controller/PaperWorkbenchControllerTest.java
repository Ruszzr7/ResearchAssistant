package com.research.assistant.controller;

import com.research.assistant.common.GlobalExceptionHandler;
import com.research.assistant.service.pdf.formula.region.FormulaRegionRecognition;
import com.research.assistant.service.pdf.formula.region.FormulaRegionService;
import com.research.assistant.service.pdf.formula.region.FormulaRegionSource;
import com.research.assistant.service.pdf.formula.region.FormulaRegionStatus;
import com.research.assistant.service.pdf.layout.LocalEvidenceResult;
import com.research.assistant.service.pdf.layout.ClientTextAnchor;
import com.research.assistant.service.pdf.layout.NormalizedBoundingBox;
import com.research.assistant.service.pdf.layout.PaperLayoutArtifact;
import com.research.assistant.service.pdf.layout.PaperLayoutArtifactService;
import com.research.assistant.service.pdf.layout.PaperLayoutEvidenceService;
import com.research.assistant.service.pdf.layout.SelectionAnchor;
import com.research.assistant.service.pdf.layout.SelectionAnchorKind;
import com.research.assistant.service.pdf.layout.SelectionAnchorResolver;
import com.research.assistant.service.pdf.layout.StaleLayoutArtifactException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class PaperWorkbenchControllerTest {

    @Mock private PaperLayoutArtifactService artifactService;
    @Mock private SelectionAnchorResolver anchorResolver;
    @Mock private PaperLayoutEvidenceService evidenceService;
    @Mock private FormulaRegionService formulaRegionService;

    private MockMvc mvc;
    private PaperLayoutArtifact artifact;
    private SelectionAnchor anchor;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new PaperWorkbenchController(
                        artifactService, anchorResolver, evidenceService, formulaRegionService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        artifact = new PaperLayoutArtifact(
                42L, "a".repeat(64), "parser+semantic", 0.9,
                Instant.parse("2026-07-16T00:00:00Z"), 1, List.of());
        anchor = new SelectionAnchor(
                42L, 1, List.of(new NormalizedBoundingBox(0.1, 0.2, 0.3, 0.04)),
                "selected text", List.of("body-1"), null, SelectionAnchorKind.TEXT,
                0.9, artifact.documentHash(), artifact.parserVersion());
    }

    @Test
    void resolvesSelectionWithoutTrustingClientBlockIds() throws Exception {
        when(artifactService.ensureArtifact(42L, false)).thenReturn(artifact);
        when(anchorResolver.resolve(eq(artifact), eq(1), anyList(), eq("selected text"), eq(null), eq(null)))
                .thenReturn(anchor);

        mvc.perform(post("/api/papers/42/workbench/selection-anchor")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"page":1,"boxes":[{"x":0.1,"y":0.2,"width":0.3,"height":0.04}],
                                 "anchorText":"selected text","blockIds":["forged"]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.blockIds[0]").value("body-1"))
                .andExpect(jsonPath("$.data.kind").value("TEXT"));
    }

    @Test
    void acceptsPdfiumCharacterRangeWithNormalizedGeometry() throws Exception {
        when(artifactService.ensureArtifact(42L, false)).thenReturn(artifact);
        when(anchorResolver.resolve(eq(artifact), eq(1), anyList(), eq("selected text"), eq(null),
                any(ClientTextAnchor.class))).thenReturn(anchor);

        mvc.perform(post("/api/papers/42/workbench/selection-anchor")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"page":1,"boxes":[{"x":0.1,"y":0.2,"width":0.3,"height":0.04}],
                                 "anchorText":"selected text","clientTextAnchor":{"version":2,"page":1,
                                 "documentFingerprint":"fingerprint","textMapVersion":1,"ranges":[],
                                 "engine":"PDFIUM","charStart":120,"charEnd":132}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void rejectsOutOfRangeSelectionGeometry() throws Exception {
        mvc.perform(post("/api/papers/42/workbench/selection-anchor")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"page":1,"boxes":[{"x":-0.1,"y":0.2,"width":0.3,"height":0.04}]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void rejectsSelectionBoxThatCrossesThePageBoundary() throws Exception {
        mvc.perform(post("/api/papers/42/workbench/selection-anchor")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"page":1,"boxes":[{"x":0.9,"y":0.2,"width":0.2,"height":0.04}]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void staleEvidenceAnchorReturnsConflict() throws Exception {
        when(artifactService.ensureArtifact(42L, false)).thenReturn(artifact);
        when(evidenceService.retrieve(eq(artifact), any(SelectionAnchor.class), anyString(), anyInt()))
                .thenThrow(new StaleLayoutArtifactException());

        mvc.perform(post("/api/papers/42/workbench/evidence/local")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"anchor":{"paperId":42,"page":1,
                                  "boxes":[{"x":0.1,"y":0.2,"width":0.3,"height":0.04}],
                                  "anchorText":"selected text","blockIds":["body-1"],
                                  "kind":"TEXT","confidence":0.9,
                                  "documentHash":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                                  "parserVersion":"old"},"query":"why","maxResults":5}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value("PDF 已更新，请重新选择内容"));
    }

    @Test
    void recognizesFormulaRegionAsAnUnconfirmedCandidate() throws Exception {
        FormulaRegionRecognition recognition = new FormulaRegionRecognition(
                9L, 42L, 1, new NormalizedBoundingBox(0.2, 0.3, 0.4, 0.1),
                "\\sum_{k=1}^{K} r_k", 0.88, FormulaRegionSource.MULTIMODAL,
                FormulaRegionStatus.CANDIDATE, "data:image/png;base64,AA==",
                "请核对", null, false);
        when(formulaRegionService.recognize(eq(42L), eq(1), any(NormalizedBoundingBox.class)))
                .thenReturn(recognition);

        mvc.perform(post("/api/papers/42/workbench/formula-regions/recognize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"page":1,"bbox":{"x":0.2,"y":0.3,"width":0.4,"height":0.1}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANDIDATE"))
                .andExpect(jsonPath("$.data.confirmed").value(false))
                .andExpect(jsonPath("$.data.anchor").doesNotExist());
    }

    @Test
    void confirmsCorrectedLatexAndReturnsAFormulaAnchor() throws Exception {
        SelectionAnchor formulaAnchor = new SelectionAnchor(
                42L, 1, List.of(new NormalizedBoundingBox(0.2, 0.3, 0.4, 0.1)),
                "\\prod_{i=1}^{n} p_i", List.of("formula-region-9"), null,
                SelectionAnchorKind.FORMULA, 1, artifact.documentHash(), artifact.parserVersion());
        FormulaRegionRecognition recognition = new FormulaRegionRecognition(
                9L, 42L, 1, formulaAnchor.boxes().get(0), formulaAnchor.anchorText(), 1,
                FormulaRegionSource.USER, FormulaRegionStatus.CONFIRMED, "", "已确认",
                formulaAnchor, true);
        when(formulaRegionService.confirm(42L, 9L, "\\prod_{i=1}^{n} p_i"))
                .thenReturn(recognition);

        mvc.perform(put("/api/papers/42/workbench/formula-regions/9/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"latex\":\"\\\\prod_{i=1}^{n} p_i\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.confirmed").value(true))
                .andExpect(jsonPath("$.data.anchor.kind").value("FORMULA"));
    }
}
