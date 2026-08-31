package com.research.assistant.service.agent.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.entity.PaperMemoryRecord;
import com.research.assistant.mapper.PaperMemoryMapper;
import com.research.assistant.service.agent.source.PaperSourceCatalog;
import com.research.assistant.service.agent.source.PaperSourceCatalogService;
import com.research.assistant.service.agent.source.RetrievalHit;
import com.research.assistant.service.agent.source.SourceContentType;
import com.research.assistant.service.agent.source.SourceLocator;
import com.research.assistant.service.agent.source.SourceObject;
import com.research.assistant.service.pdf.layout.EvidenceLocator;
import com.research.assistant.service.pdf.layout.NormalizedBoundingBox;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaperReadToolRegistryTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void exposesOneBatchEvidenceToolForSearchAndPageNeeds() throws Exception {
        PaperReadToolRegistry registry = new PaperReadToolRegistry(mock(PaperSourceCatalogService.class), objectMapper);

        assertThat(registry.definitions("解释论文的核心贡献")).extracting(AgentToolDefinition::name)
                .containsExactly("retrieve_paper_evidence");
        JsonNode schema = objectMapper.readTree(registry.definitions().get(0).parametersJsonSchema());
        assertThat(schema.isObject()).isTrue();
        assertThat(schema.at("/properties/needs").isObject()).isTrue();
        assertThat(schema.at("/properties/searches").isMissingNode()).isTrue();
        assertThat(registry.definitions().get(0).description())
                .contains("all currently known", "one fallback", "coverage");
        assertThat(schema.at("/properties/needs/items/properties/targets").isObject()).isTrue();
        assertThat(schema.at("/properties/maxEvidence/maximum").asInt()).isEqualTo(8);
        assertThat(registry.definitions("请总结第 5 页")).extracting(AgentToolDefinition::name)
                .containsExactly("retrieve_paper_evidence");
    }

    @Test
    void mergesQueriesDeduplicatesSourcesAndReturnsThemAsCitable() throws Exception {
        PaperSourceCatalogService sourceService = mock(PaperSourceCatalogService.class);
        PaperSourceCatalog catalog = catalog(20, "公式（21）给出速率下界。", "定理说明该下界成立。");
        SourceObject formula = catalog.requireObject("src-1");
        SourceObject theorem = catalog.requireObject("src-2");
        when(sourceService.search(eq(catalog), any())).thenReturn(
                List.of(new RetrievalHit("src-1", .90, List.of("FORMULA")),
                        new RetrievalHit("src-2", .70, List.of("TEXT"))),
                List.of(new RetrievalHit("src-1", .95, List.of("EXACT_PHRASE"))));
        when(sourceService.readSource(catalog, "src-1")).thenReturn(formula);
        when(sourceService.readSource(catalog, "src-2")).thenReturn(theorem);
        PaperReadToolRegistry registry = new PaperReadToolRegistry(sourceService, objectMapper);

        AgentToolExecution execution = registry.execute(catalog, "retrieve_paper_evidence", """
                {"searches":[
                  {"query":"最重要公式","contentTypes":["FORMULA"]},
                  {"query":"公式对应定理","contentTypes":["TEXT"]}
                ],"maxEvidence":4}
                """);
        JsonNode json = objectMapper.readTree(execution.resultJson());

        assertThat(json.path("sources")).hasSize(2);
        assertThat(json.at("/sources/0/sourceObjectId").asText()).isEqualTo("src-1");
        assertThat(json.at("/sources/0/sourceRole").asText()).isEqualTo("BODY_TEXT");
        assertThat(json.at("/sources/0/matchedSearches").toString()).contains("0", "1");
        assertThat(execution.sourceObjectIds()).containsExactlyInAnyOrder("src-1", "src-2");
        verify(sourceService).readSource(catalog, "src-1");
        verify(sourceService).readSource(catalog, "src-2");
    }

    @Test
    void reservesCoverageForEachSearchBeforeGlobalScoreRanking() throws Exception {
        PaperSourceCatalogService sourceService = mock(PaperSourceCatalogService.class);
        PaperSourceCatalog catalog = catalogWithThreeSources();
        when(sourceService.search(eq(catalog), any())).thenReturn(
                List.of(new RetrievalHit("src-1", .99, List.of("FORMULA")),
                        new RetrievalHit("src-2", .90, List.of("FORMULA"))),
                List.of(new RetrievalHit("src-3", .40, List.of("TEXT"))));
        for (String sourceId : List.of("src-1", "src-2", "src-3")) {
            when(sourceService.readSource(catalog, sourceId)).thenReturn(catalog.requireObject(sourceId));
        }

        AgentToolExecution execution = new PaperReadToolRegistry(sourceService, objectMapper).execute(
                catalog, "retrieve_paper_evidence",
                "{\"needs\":[{\"id\":\"formula\",\"query\":\"formula\"},{\"id\":\"context\",\"query\":\"context\"}],\"maxEvidence\":2}");
        JsonNode json = objectMapper.readTree(execution.resultJson());

        assertThat(execution.sourceObjectIds()).containsExactlyInAnyOrder("src-1", "src-3");
        assertThat(json.path("sources").findValues("matchedSearches").toString()).contains("0", "1");
        assertThat(json.at("/evidenceNeeds/0/status").asText()).isEqualTo("found");
        assertThat(json.at("/evidenceNeeds/1/status").asText()).isEqualTo("found");
        assertThat(json.at("/evidenceNeeds/0/needId").asText()).isEqualTo("formula");
        assertThat(json.at("/evidenceNeeds/1/needId").asText()).isEqualTo("context");
        assertThat(json.at("/coverage").asText()).isEqualTo("found");
        assertThat(json.has("candidateCount")).isFalse();
    }

    @Test
    void performsOneFallbackForAnUncoveredNeedAndThenStops() throws Exception {
        PaperSourceCatalogService sourceService = mock(PaperSourceCatalogService.class);
        PaperSourceCatalog catalog = catalog(5, "fallback evidence", "unrelated text");
        when(sourceService.search(eq(catalog), any())).thenReturn(
                List.of(), List.of(new RetrievalHit("src-1", .82, List.of("TOKEN"))));
        when(sourceService.readSource(catalog, "src-1")).thenReturn(catalog.requireObject("src-1"));

        AgentToolExecution execution = new PaperReadToolRegistry(sourceService, objectMapper).execute(
                catalog, "retrieve_paper_evidence", """
                        {"needs":[{"id":"fallback","query":"long query without a hit",
                        "keywords":["fallback evidence"]}],"maxEvidence":1}
                        """);
        JsonNode json = objectMapper.readTree(execution.resultJson());

        assertThat(json.at("/coverage").asText()).isEqualTo("found");
        assertThat(json.at("/evidenceNeeds/0/status").asText()).isEqualTo("found");
        assertThat(json.at("/evidenceNeeds/0/sourceObjectIds/0").asText()).isEqualTo("src-1");
        verify(sourceService, times(2)).search(eq(catalog), any());
    }

    @Test
    void keepsBatchPayloadWithinModelLimitAndBoundsContent() throws Exception {
        PaperSourceCatalogService sourceService = mock(PaperSourceCatalogService.class);
        PaperSourceCatalog catalog = catalog(20, "中".repeat(20_000), "英".repeat(20_000));
        when(sourceService.search(eq(catalog), any())).thenReturn(List.of(
                new RetrievalHit("src-1", .9, List.of("TOKEN")),
                new RetrievalHit("src-2", .8, List.of("TOKEN"))));
        when(sourceService.readSource(catalog, "src-1")).thenReturn(catalog.requireObject("src-1"));
        when(sourceService.readSource(catalog, "src-2")).thenReturn(catalog.requireObject("src-2"));
        PaperReadToolRegistry registry = new PaperReadToolRegistry(sourceService, objectMapper);

        AgentToolExecution execution = registry.execute(catalog, "retrieve_paper_evidence",
                "{\"searches\":[{\"query\":\"test\"}],\"maxEvidence\":8}");
        JsonNode json = objectMapper.readTree(execution.resultJson());

        assertThat(execution.resultJson().getBytes(StandardCharsets.UTF_8).length)
                .isLessThanOrEqualTo(PaperReadToolRegistry.MAX_RESULT_BYTES);
        assertThat(json.at("/sources/0/content").asText()).hasSizeLessThanOrEqualTo(
                PaperReadToolRegistry.MAX_CONTENT_CHARACTERS);
        assertThat(execution.resultJson()).doesNotContain("documentHash", "parserVersion", "normalizedContent", "provenance");
        assertThat(json.has("truncated")).isFalse();
    }

    @Test
    void boundsModelGeneratedSearchesWithoutFailingTheEvidenceBatch() throws Exception {
        PaperSourceCatalogService sourceService = mock(PaperSourceCatalogService.class);
        when(sourceService.search(any(), any())).thenReturn(List.of());
        PaperReadToolRegistry registry = new PaperReadToolRegistry(sourceService, objectMapper);
        PaperSourceCatalog catalog = catalog(5, "text", "more text");

        String longQuery = "important ".repeat(1_000);
        String arguments = objectMapper.writeValueAsString(Map.of("searches", List.of(
                Map.of("query", longQuery, "contentTypes", List.of("TEXT", "UNKNOWN")),
                Map.of("query", "2"), Map.of("query", "3"), Map.of("query", "4"), Map.of("query", "5"))));
        AgentToolExecution execution = registry.execute(catalog, "retrieve_paper_evidence", arguments);

        assertThat(objectMapper.readTree(execution.resultJson()).path("requestedSearches").asInt()).isEqualTo(4);
        var captor = org.mockito.ArgumentCaptor.forClass(
                com.research.assistant.service.agent.source.PaperSearchRequest.class);
        verify(sourceService, times(5)).search(eq(catalog), captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(request ->
                assertThat(request.query().length()).isLessThanOrEqualTo(PaperReadToolRegistry.MAX_QUERY_CHARACTERS));

        assertThatThrownBy(() -> registry.execute(catalog, "read_pages", "{\"startPage\":1,\"endPage\":3}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most two");
    }

    @Test
    void resolvesProfileClaimRefToItsOriginalSourceWithoutAnotherSearchSystem() throws Exception {
        PaperSourceCatalogService sourceService = mock(PaperSourceCatalogService.class);
        PaperMemoryMapper memoryMapper = mock(PaperMemoryMapper.class);
        PaperSourceCatalog catalog = catalog(5, "direct supporting statement", "unrelated text");
        when(sourceService.search(eq(catalog), any())).thenReturn(List.of());
        when(sourceService.readSource(catalog, "src-1")).thenReturn(catalog.requireObject("src-1"));
        PaperMemoryRecord memory = new PaperMemoryRecord();
        memory.setDocumentHash(catalog.documentHash());
        memory.setLayoutParserVersion(catalog.parserVersion());
        memory.setProfileJson("""
                {"keyFindings":[{"category":"FINDING","statement":"结论",
                "evidenceBlockIds":["p1-b1"],"confidence":0.9}]}
                """);
        when(memoryMapper.selectLatest(catalog.paperId())).thenReturn(memory);

        AgentToolExecution execution = new PaperReadToolRegistry(sourceService, objectMapper, memoryMapper)
                .execute(catalog, "retrieve_paper_evidence", """
                        {"searches":[{"query":"结论"}],"maxEvidence":1}
                        """);

        assertThat(execution.sourceObjectIds()).containsExactly("src-1");
        assertThat(objectMapper.readTree(execution.resultJson()).at("/sources/0/content").asText())
                .isEqualTo("direct supporting statement");
    }

    @Test
    void evidenceFocusPrefersFindingClaimWithoutImposingAWorkflowState() throws Exception {
        PaperSourceCatalogService sourceService = mock(PaperSourceCatalogService.class);
        PaperMemoryMapper memoryMapper = mock(PaperMemoryMapper.class);
        PaperSourceCatalog catalog = catalog(5, "direct supporting statement", "unrelated text");
        when(sourceService.search(eq(catalog), any())).thenReturn(List.of());
        when(sourceService.readSource(catalog, "src-1")).thenReturn(catalog.requireObject("src-1"));
        PaperMemoryRecord memory = new PaperMemoryRecord();
        memory.setDocumentHash(catalog.documentHash());
        memory.setLayoutParserVersion(catalog.parserVersion());
        memory.setProfileJson("""
                {"keyFindings":[{"category":"FINDING",
                "statement":"所提方案在缩短块长和降低BLER下不降低传输速率",
                "evidenceBlockIds":["p1-b1"],"confidence":0.9}],
                "benchmarkResults":[{"metric":"较短码长与较低错误率之间的关系",
                "value":"","baseline":"","dataset":"","evidenceBlockIds":["p1-b1"]}]}
                """);
        when(memoryMapper.selectLatest(catalog.paperId())).thenReturn(memory);

        AgentToolExecution execution = new PaperReadToolRegistry(sourceService, objectMapper, memoryMapper)
                .execute(catalog, "retrieve_paper_evidence",
                        "{\"searches\":[{\"query\":\"rate\"}],\"maxEvidence\":1}",
                        "较短码长与较低错误率之间的关系");
        JsonNode json = objectMapper.readTree(execution.resultJson());

        assertThat(json.at("/matchedProfileClaimRefs/0").asText()).isEqualTo("finding:0");
        assertThat(json.has("evidenceBatchComplete")).isFalse();
        assertThat(json.has("nextStep")).isFalse();
        assertThat(json.has("additionalEvidenceAllowed")).isFalse();
        assertThat(json.path("usage").asText()).contains("unresolvedTargets", "newSourceCount");
    }

    @Test
    void reportsExplicitTargetCoverageInsteadOfTreatingAnyHitAsComplete() throws Exception {
        PaperSourceCatalogService sourceService = mock(PaperSourceCatalogService.class);
        PaperSourceCatalog catalog = catalog(5, "公式（21）给出速率下界。", "定理说明该下界成立。");
        when(sourceService.search(eq(catalog), any())).thenReturn(
                List.of(new RetrievalHit("src-1", .95, List.of("FORMULA"))));
        when(sourceService.readSource(catalog, "src-1")).thenReturn(catalog.requireObject("src-1"));

        AgentToolExecution execution = new PaperReadToolRegistry(sourceService, objectMapper).execute(
                catalog, "retrieve_paper_evidence", """
                        {"needs":[{"id":"formulas","query":"rate bound",
                        "targets":["21","22"]}],"maxEvidence":8}
                        """);
        JsonNode json = objectMapper.readTree(execution.resultJson());

        assertThat(json.at("/coverage").asText()).isEqualTo("partial");
        assertThat(json.at("/evidenceNeeds/0/status").asText()).isEqualTo("partial");
        assertThat(json.at("/evidenceNeeds/0/coveredTargets").toString()).contains("21");
        assertThat(json.at("/evidenceNeeds/0/unresolvedTargets").toString()).contains("22");
        assertThat(json.at("/unresolvedTargets").toString()).contains("22");
        assertThat(json.at("/exhausted").asBoolean()).isFalse();
    }

    @Test
    void expandsACompositeFormulaFamilyIntoOneEvidenceResult() throws Exception {
        PaperSourceCatalogService sourceService = mock(PaperSourceCatalogService.class);
        PaperSourceCatalog catalog = formulaFamilyCatalog("35", 7);
        when(sourceService.search(eq(catalog), any())).thenReturn(
                List.of(new RetrievalHit("formula-35a", .95, List.of("FORMULA"))));
        when(sourceService.readSource(eq(catalog), any())).thenAnswer(invocation ->
                catalog.requireObject(invocation.getArgument(1)));

        AgentToolExecution execution = new PaperReadToolRegistry(sourceService, objectMapper).execute(
                catalog, "retrieve_paper_evidence",
                "{\"needs\":[{\"id\":\"formula\",\"query\":\"complete formula 35\"}]}");
        JsonNode json = objectMapper.readTree(execution.resultJson());

        assertThat(execution.sourceObjectIds()).hasSize(7)
                .contains("formula-35a", "formula-35g");
        assertThat(json.path("sources").findValuesAsText("formulaNumber"))
                .containsExactlyInAnyOrder("35a", "35b", "35c", "35d", "35e", "35f", "35g");
    }

    private PaperSourceCatalog catalog(int pageCount, String firstContent, String secondContent) {
        SourceObject first = new SourceObject("src-1", 9, "h".repeat(64), "parser", 1,
                SourceContentType.FORMULA, firstContent, null, List.of("Results"), "21",
                Map.of("role", "BODY_TEXT", "blockIds", "p1-b1"));
        SourceObject second = new SourceObject("src-2", 9, "h".repeat(64), "parser", 1,
                SourceContentType.TEXT, secondContent, null, List.of("Theorem 1"), "", Map.of());
        SourceLocator firstLocator = new SourceLocator("loc-1", "src-1", 1, "PDF_NORMALIZED",
                List.of(new NormalizedBoundingBox(.1, .1, .8, .1)), "formula", EvidenceLocator.Precision.FORMULA_REGION);
        SourceLocator secondLocator = new SourceLocator("loc-2", "src-2", 1, "PDF_NORMALIZED",
                List.of(new NormalizedBoundingBox(.1, .2, .8, .1)), "theorem", EvidenceLocator.Precision.TEXT_RANGE);
        return new PaperSourceCatalog(9, "h".repeat(64), "parser", pageCount,
                new LinkedHashMap<>(Map.of("src-1", first, "src-2", second)),
                Map.of("src-1", List.of(firstLocator), "src-2", List.of(secondLocator)));
    }

    private PaperSourceCatalog catalogWithThreeSources() {
        PaperSourceCatalog base = catalog(5, "formula", "context");
        SourceObject third = new SourceObject("src-3", 9, "h".repeat(64), "parser", 1,
                SourceContentType.TEXT, "third source", null, List.of("Context"), "", Map.of());
        SourceLocator thirdLocator = new SourceLocator("loc-3", "src-3", 1, "PDF_NORMALIZED",
                List.of(new NormalizedBoundingBox(.1, .3, .8, .1)), "third source", EvidenceLocator.Precision.TEXT_RANGE);
        Map<String, SourceObject> objects = new LinkedHashMap<>(base.objects());
        objects.put("src-3", third);
        Map<String, List<SourceLocator>> locators = new LinkedHashMap<>(base.locators());
        locators.put("src-3", List.of(thirdLocator));
        return new PaperSourceCatalog(base.paperId(), base.documentHash(), base.parserVersion(),
                base.pageCount(), objects, locators);
    }

    private PaperSourceCatalog formulaFamilyCatalog(String base, int parts) {
        Map<String, SourceObject> objects = new LinkedHashMap<>();
        Map<String, List<SourceLocator>> locators = new LinkedHashMap<>();
        for (int index = 0; index < parts; index++) {
            String suffix = String.valueOf((char) ('a' + index));
            String id = "formula-" + base + suffix;
            objects.put(id, new SourceObject(id, 9, "h".repeat(64), "parser", 1,
                    SourceContentType.FORMULA, "x_" + suffix + " = " + (index + 1), null,
                    List.of("Method", "Equation (" + base + suffix + ")"), base + suffix,
                    Map.of("role", "EQUATION")));
            locators.put(id, List.of(new SourceLocator("loc-" + id, id, 3,
                    "PDF_NORMALIZED", List.of(new NormalizedBoundingBox(.1, .1 + index * .05, .8, .04)),
                    "x_" + suffix, EvidenceLocator.Precision.FORMULA_REGION)));
        }
        return new PaperSourceCatalog(9, "h".repeat(64), "parser", 5, objects, locators);
    }
}
