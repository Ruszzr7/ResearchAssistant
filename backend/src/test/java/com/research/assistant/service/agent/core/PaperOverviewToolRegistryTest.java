package com.research.assistant.service.agent.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.entity.PaperMemoryRecord;
import com.research.assistant.mapper.PaperMemoryMapper;
import com.research.assistant.service.agent.source.PaperSourceCatalog;
import com.research.assistant.service.memory.PaperUnderstandingService;
import com.research.assistant.service.agent.source.SourceContentType;
import com.research.assistant.service.agent.source.SourceObject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PaperOverviewToolRegistryTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void returnsOnlyUnderstandingFieldsWithoutInternalGroundingMetadata() throws Exception {
        PaperMemoryMapper mapper = mock(PaperMemoryMapper.class);
        PaperMemoryRecord memory = new PaperMemoryRecord();
        memory.setDocumentHash("hash");
        memory.setLayoutParserVersion("parser");
        memory.setStatus("READY");
        memory.setUnderstandingVersion(PaperUnderstandingService.PIPELINE_VERSION);
        memory.setProfileQualityJson("{\"usable\":true,\"issues\":[]}");
        memory.setProfileJson("""
                {
                  "paperId":9,"title":"A paper","domain":"AI","researchProblem":"A problem",
                  "coreContributions":[{"category":"CONTRIBUTION","statement":"A contribution","evidenceBlockIds":["p1-b1"],"confidence":0.9}],
                  "methodType":"MODEL","methodSummary":"A method","datasets":["D"],"models":["M"],"metrics":["Accuracy"],
                  "keyFindings":[{"category":"FINDING","statement":"A finding","evidenceBlockIds":["p2-b1"],"confidence":0.8}],
                  "limitations":[{"category":"LIMITATION","statement":"A limitation","evidenceBlockIds":["p3-b1"],"confidence":0.7}],
                  "experimentSetup":{"hardware":"GPU"},
                  "benchmarkResults":[{"metric":"Accuracy","value":"91%","baseline":"Base","dataset":"D","evidenceBlockIds":["p4-b1"]}],
                  "sectionDigests":[{"sectionId":"s1","headingPath":["Intro"],"summary":"digest","sourceChunkIds":["c1"]}],
                  "openQuestions":["What next?"],"qualityIssues":["internal"]
                }
                """);
        when(mapper.selectLatest(9L)).thenReturn(memory);
        SourceObject contributionSource = new SourceObject("span-p1", 9L, "hash", "parser", 1,
                SourceContentType.TEXT, "A contribution", null, List.of("Introduction"), "",
                Map.of("blockId", "p1-b1"));
        PaperSourceCatalog catalog = new PaperSourceCatalog(9L, "hash", "parser", 1,
                Map.of("span-p1", contributionSource), Map.of());

        AgentToolExecution execution = new PaperOverviewToolRegistry(mapper, objectMapper).execute(9L, catalog);
        JsonNode json = objectMapper.readTree(execution.resultJson());

        assertThat(json.at("/profile/coreContributions/0/statement").asText()).isEqualTo("A contribution");
        assertThat(json.at("/profile/coreContributions/0/claimRef").asText()).isEqualTo("contribution:0");
        assertThat(json.at("/profile/coreContributions/0/candidateSourceObjectIds").toString()).contains("span-p1");
        assertThat(json.at("/profile/coreContributions/0/sourceObjectIds").isMissingNode()).isTrue();
        assertThat(json.at("/candidateSourceObjectIds").toString()).contains("span-p1");
        assertThat(execution.sourceObjectIds()).isEmpty();
        assertThat(json.at("/profile/keyFindings/0/statement").asText()).isEqualTo("A finding");
        assertThat(json.at("/profile/keyFindings/0/claimRef").asText()).isEqualTo("finding:0");
        assertThat(json.at("/profile/benchmarkResults/0/value").asText()).isEqualTo("91%");
        assertThat(execution.resultJson()).doesNotContain(
                "documentHash", "qualityIssues", "evidenceBlockIds", "confidence",
                "sectionDigests", "sourceChunkIds", "generatedAt");
    }

    @Test
    void rejectsProfileFromAnOlderUnderstandingPipeline() throws Exception {
        PaperMemoryMapper mapper = mock(PaperMemoryMapper.class);
        PaperMemoryRecord memory = new PaperMemoryRecord();
        memory.setDocumentHash("hash");
        memory.setLayoutParserVersion("parser");
        memory.setUnderstandingVersion("paper-understanding-old");
        memory.setProfileJson("{}");
        when(mapper.selectLatest(9L)).thenReturn(memory);
        PaperSourceCatalog catalog = new PaperSourceCatalog(9L, "hash", "parser", 1, Map.of(), Map.of());

        JsonNode json = objectMapper.readTree(
                new PaperOverviewToolRegistry(mapper, objectMapper).execute(9L, catalog).resultJson());

        assertThat(json.path("status").asText()).isEqualTo("stale");
    }
}
