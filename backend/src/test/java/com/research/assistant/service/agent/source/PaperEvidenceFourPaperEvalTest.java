package com.research.assistant.service.agent.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.service.agent.core.AgentToolExecution;
import com.research.assistant.service.agent.core.PaperReadToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

/** Temporary deterministic evidence acceptance test over the four refactor papers. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.task.scheduling.enabled=false",
        "app.async.dispatch-enabled=false",
        "app.async.mark-orphaned-on-startup=false"
})
@EnabledIfEnvironmentVariable(named = "RUN_PAPER_EVIDENCE_REAL_EVAL", matches = "true")
class PaperEvidenceFourPaperEvalTest {

    private static final String RESOURCE = "/eval/paper-evidence-four-paper.json";
    private static final long MAX_RETRIEVAL_MILLIS = 1_500;

    @Autowired
    private PaperSourceCatalogService sourceService;

    @Autowired
    private PaperReadToolRegistry toolRegistry;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void retrievesDirectEvidenceForTheFourPaperSample() throws Exception {
        JsonNode suite = readSuite();
        List<String> failures = new ArrayList<>();
        int total = 0;
        int recall = 0;
        double reciprocalRank = 0;
        int locatorTotal = 0;
        int locatorValid = 0;
        List<Long> durations = new ArrayList<>();

        for (JsonNode testCase : suite.path("cases")) {
            total++;
            long paperId = testCase.path("paperId").asLong();
            String name = testCase.path("name").asText();
            PaperSourceCatalog catalog = sourceService.latest(paperId);
            var argumentsNode = objectMapper.createObjectNode();
            argumentsNode.set("searches", testCase.path("searches"));
            argumentsNode.put("maxEvidence", 6);
            String arguments = objectMapper.writeValueAsString(argumentsNode);
            long started = System.nanoTime();
            AgentToolExecution execution = toolRegistry.execute(catalog, "retrieve_paper_evidence", arguments,
                    testCase.path("evidenceFocus").asText(null));
            long durationMillis = (System.nanoTime() - started) / 1_000_000;
            durations.add(durationMillis);
            JsonNode result = objectMapper.readTree(execution.resultJson());
            JsonNode sources = result.path("sources");

            if (durationMillis > MAX_RETRIEVAL_MILLIS) {
                failures.add(label(paperId, name) + " retrieval took " + durationMillis + " ms");
            }

            if (execution.resultJson().getBytes(StandardCharsets.UTF_8).length > 16 * 1024) {
                failures.add(label(paperId, name) + " response exceeded 16 KiB");
            }
            if (!sources.isArray() || sources.size() > 6) {
                failures.add(label(paperId, name) + " returned more than six sources");
            }
            Set<String> uniqueIds = new HashSet<>();
            int matchedRank = -1;
            for (int index = 0; index < sources.size(); index++) {
                String sourceId = sources.get(index).path("sourceObjectId").asText();
                if (!uniqueIds.add(sourceId)) failures.add(label(paperId, name) + " returned duplicate " + sourceId);
                SourceObject source = catalog.requireObject(sourceId);
                List<SourceLocator> locators = catalog.requireLocators(sourceId);
                locatorTotal += locators.size();
                for (SourceLocator locator : locators) {
                    boolean valid = locator.pageNumber() >= 1 && locator.pageNumber() <= catalog.pageCount()
                            && locator.rects() != null && !locator.rects().isEmpty()
                            && locator.rects().stream().allMatch(box -> box.width() > 0 && box.height() > 0);
                    if (valid) locatorValid++; else failures.add(label(paperId, name) + " invalid locator " + locator.locatorId());
                }
                if (matchedRank < 0 && directMatch(testCase, source, locators)) matchedRank = index + 1;
            }
            if (!uniqueIds.equals(new HashSet<>(execution.sourceObjectIds()))) {
                failures.add(label(paperId, name) + " JSON sources differ from Agent-visible source IDs");
            }
            if (matchedRank > 0 && matchedRank <= 4) {
                recall++;
                reciprocalRank += 1.0 / matchedRank;
            } else if (matchedRank < 0) {
                failures.add(label(paperId, name) + " direct evidence missing; returned=" + uniqueIds);
            } else {
                failures.add(label(paperId, name) + " direct evidence fell below rank 4: " + matchedRank);
            }
            Set<Integer> coveredSearches = new HashSet<>();
            for (JsonNode source : sources) {
                source.path("matchedSearches").forEach(index -> coveredSearches.add(index.asInt()));
            }
            for (int index = 0; index < testCase.path("searches").size(); index++) {
                if (!coveredSearches.contains(index)) failures.add(label(paperId, name)
                        + " uncovered searchIndex=" + index);
            }
            System.out.printf(Locale.ROOT,
                    "EVIDENCE_EVAL paper=%d case=%s rank=%d returned=%d bytes=%d profileRefs=%s%n",
                    paperId, name, matchedRank, sources.size(),
                    execution.resultJson().getBytes(StandardCharsets.UTF_8).length,
                    result.path("matchedProfileClaimRefs"));
        }

        double mrr = total == 0 ? 0 : reciprocalRank / total;
        Collections.sort(durations);
        long p95Millis = durations.isEmpty() ? 0 : durations.get((int) Math.floor((durations.size() - 1) * 0.95));
        System.out.printf(Locale.ROOT,
                "EVIDENCE_EVAL_SUMMARY cases=%d recallAt4=%d/%d mrr=%.3f locators=%d/%d latencyP95Ms=%d%n",
                total, recall, total, mrr, locatorValid, locatorTotal, p95Millis);
        failures.forEach(failure -> System.out.println("EVIDENCE_EVAL_FAILURE " + failure));
        assertThat(failures).as("four-paper evidence failures").isEmpty();
    }

    private boolean directMatch(JsonNode testCase, SourceObject source, List<SourceLocator> locators) {
        if (testCase.hasNonNull("expectedPage")
                && locators.stream().noneMatch(locator -> locator.pageNumber() == testCase.path("expectedPage").asInt())) {
            return false;
        }
        if (testCase.hasNonNull("expectedFormulaNumber")
                && !testCase.path("expectedFormulaNumber").asText().equalsIgnoreCase(source.formulaNumber())) {
            return false;
        }
        Set<String> actualBlocks = new HashSet<>();
        String blockIds = source.provenance().getOrDefault("blockIds", "");
        if (!blockIds.isBlank()) actualBlocks.addAll(List.of(blockIds.split(",")));
        String blockId = source.provenance().getOrDefault("blockId", "");
        if (!blockId.isBlank()) actualBlocks.add(blockId);
        for (JsonNode required : testCase.path("requiredBlockIds")) {
            if (!actualBlocks.contains(required.asText())) return false;
        }
        String content = normalize(source.rawContent());
        for (JsonNode group : testCase.path("requiredTermGroups")) {
            boolean matched = false;
            for (JsonNode term : group) {
                if (content.contains(normalize(term.asText()))) {
                    matched = true;
                    break;
                }
            }
            if (!matched) return false;
        }
        return true;
    }

    private JsonNode readSuite() throws Exception {
        try (InputStream input = getClass().getResourceAsStream(RESOURCE)) {
            assertThat(input).as("evidence eval resource").isNotNull();
            return objectMapper.readTree(input);
        }
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT).replace('−', '-').replaceAll("\\s+", " ").trim();
    }

    private static String label(long paperId, String name) {
        return "paper=" + paperId + " case=" + name;
    }
}
