package com.research.assistant.service.pdf.layout;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Temporary human-seeded acceptance test for the four papers used during the parser refactor.
 * It is intentionally small and disposable; it evaluates the latest persisted parse without
 * calling a model or adding runtime quality gates.
 */
@EnabledIfEnvironmentVariable(named = "RUN_PAPER_PARSING_QUALITY_EVAL", matches = "true")
class PaperParsingFourPaperQualityEvalTest {

    private static final String CASES = "/eval/paper-parsing-four-paper-quality.json";
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final PaperSemanticSpanBuilder spanBuilder = new PaperSemanticSpanBuilder();

    @Test
    void evaluatesLatestFourPaperParseAgainstSmallHumanCheckedSample() throws Exception {
        EvalSuite suite = readSuite();
        Counters counters = new Counters();
        List<String> failures = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(
                env("SPRING_DATASOURCE_URL",
                        "jdbc:mysql://localhost:3306/research_assistant?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=UTF-8"),
                env("SPRING_DATASOURCE_USERNAME", "root"), env("SPRING_DATASOURCE_PASSWORD", ""))) {
            for (PaperCase paperCase : suite.papers()) {
                evaluatePaper(connection, suite.pipelineVersion(), paperCase, counters, failures);
            }
        }

        System.out.printf(Locale.ROOT,
                "PARSING_QUALITY papers=%d coverage=%d/%d roles=%d/%d grouping=%d/%d facts=%d/%d directSupport=%d/%d locators=%d/%d%n",
                suite.papers().size(), counters.coveragePassed, counters.coverageTotal,
                counters.rolesPassed, counters.rolesTotal,
                counters.groupsPassed, counters.groupsTotal,
                counters.factsPassed, counters.factsTotal,
                counters.supportPassed, counters.supportTotal,
                counters.locatorsPassed, counters.locatorsTotal);
        failures.forEach(failure -> System.out.println("PARSING_QUALITY_FAILURE " + failure));
        assertThat(failures).as("four-paper parsing quality failures").isEmpty();
    }

    private void evaluatePaper(Connection connection, String pipelineVersion, PaperCase paperCase,
                               Counters counters, List<String> failures) throws Exception {
        LayoutRow layout = loadLayout(connection, paperCase.paperId());
        MemoryRow memory = loadMemory(connection, paperCase.paperId());
        Map<String, DocumentBlock> blocksById = new LinkedHashMap<>();
        layout.blocks().forEach(block -> blocksById.put(block.id(), block));
        List<PaperSemanticSpan> spans = spanBuilder.build(new PaperLayoutArtifact(
                paperCase.paperId(), layout.documentHash(), layout.parserVersion(),
                layout.confidence(), null, layout.pageCount(), layout.blocks()));

        counters.coverageTotal++;
        Map<String, Integer> coverage = new HashMap<>();
        spans.forEach(span -> span.blockIds().forEach(id -> coverage.merge(id, 1, Integer::sum)));
        Set<String> nonEmptyIds = new HashSet<>();
        layout.blocks().stream().filter(spanBuilder::isEvidenceEligible)
                .forEach(block -> nonEmptyIds.add(block.id()));
        boolean coverageOk = coverage.keySet().equals(nonEmptyIds)
                && coverage.values().stream().allMatch(count -> count == 1);
        record(coverageOk, counters::passCoverage, failures,
                "paper=%d layout coverage is not one-to-one".formatted(paperCase.paperId()));
        if (!pipelineVersion.equals(memory.pipelineVersion())) {
            failures.add("paper=%d expected pipeline=%s actual=%s".formatted(
                    paperCase.paperId(), pipelineVersion, memory.pipelineVersion()));
        }

        for (RoleCase roleCase : paperCase.roleCases()) {
            counters.rolesTotal++;
            DocumentBlock block = blocksById.get(roleCase.blockId());
            String actual = block == null ? "MISSING" : spanBuilder.effectiveRole(block).name();
            record(roleCase.expectedRole().equals(actual), counters::passRole, failures,
                    "paper=%d block=%s expectedRole=%s actual=%s".formatted(
                            paperCase.paperId(), roleCase.blockId(), roleCase.expectedRole(), actual));
        }

        Map<String, String> spanByBlock = new HashMap<>();
        spans.forEach(span -> span.blockIds().forEach(id -> spanByBlock.put(id, span.id())));
        for (SpanGroup group : paperCase.spanGroups()) {
            counters.groupsTotal++;
            Set<String> actualSpans = new HashSet<>();
            group.blockIds().forEach(id -> actualSpans.add(spanByBlock.get(id)));
            record(!actualSpans.contains(null) && actualSpans.size() == 1,
                    counters::passGroup, failures,
                    "paper=%d group=%s splitAcross=%s".formatted(
                            paperCase.paperId(), group.name(), actualSpans));
        }

        validateAllStoredEvidence(paperCase.paperId(), memory.profile(), blocksById, layout.pageCount(),
                counters, failures);
        for (FactCase fact : paperCase.facts()) {
            evaluateFact(paperCase.paperId(), fact, memory.profile(), blocksById, counters, failures);
        }
    }

    private void evaluateFact(long paperId, FactCase fact, JsonNode profile,
                              Map<String, DocumentBlock> blocksById,
                              Counters counters, List<String> failures) {
        counters.factsTotal++;
        JsonNode matched = null;
        for (String field : fact.profileFields()) {
            JsonNode claims = profile.path(field);
            if (!claims.isArray()) continue;
            for (JsonNode claim : claims) {
                if (containsGroups(claim.path("statement").asText(), fact.claimTermGroups())) {
                    matched = claim;
                    break;
                }
            }
            if (matched != null) break;
        }
        boolean recalled = matched != null;
        record(recalled, counters::passFact, failures,
                "paper=%d fact=%s was not found in profile".formatted(paperId, fact.name()));
        counters.supportTotal++;
        if (!recalled) {
            failures.add("paper=%d fact=%s has no evidence because the fact is missing".formatted(paperId, fact.name()));
            return;
        }

        List<String> evidenceIds = stringValues(matched.path("evidenceBlockIds"));
        StringBuilder evidence = new StringBuilder();
        Set<Integer> pages = new HashSet<>();
        for (String id : evidenceIds) {
            DocumentBlock block = blocksById.get(id);
            if (block == null) continue;
            evidence.append(' ').append(block.text()).append(' ')
                    .append(block.tableText()).append(' ').append(block.latex());
            pages.add(block.page());
        }
        boolean termsSupported = containsGroups(evidence.toString(), fact.evidenceTermGroups());
        boolean requiredIdsPresent = evidenceIds.containsAll(fact.requiredEvidenceBlockIds());
        boolean expectedPagePresent = fact.expectedPage() == null || pages.contains(fact.expectedPage());
        record(termsSupported && requiredIdsPresent && expectedPagePresent,
                counters::passSupport, failures,
                "paper=%d fact=%s directSupport=%s requiredIds=%s pages=%s evidenceIds=%s".formatted(
                        paperId, fact.name(), termsSupported, requiredIdsPresent, pages, evidenceIds));
    }

    private void validateAllStoredEvidence(long paperId, JsonNode node,
                                           Map<String, DocumentBlock> blocksById, int pageCount,
                                           Counters counters, List<String> failures) {
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                if ("evidenceBlockIds".equals(entry.getKey()) && entry.getValue().isArray()) {
                    for (JsonNode idNode : entry.getValue()) {
                        counters.locatorsTotal++;
                        String id = idNode.asText();
                        DocumentBlock block = blocksById.get(id);
                        boolean valid = block != null && block.page() >= 1 && block.page() <= pageCount
                                && block.bbox() != null && block.bbox().width() > 0 && block.bbox().height() > 0;
                        record(valid, counters::passLocator, failures,
                                "paper=%d invalid evidence locator=%s".formatted(paperId, id));
                    }
                } else {
                    validateAllStoredEvidence(paperId, entry.getValue(), blocksById, pageCount, counters, failures);
                }
            });
        } else if (node.isArray()) {
            node.forEach(child -> validateAllStoredEvidence(paperId, child, blocksById, pageCount, counters, failures));
        }
    }

    private LayoutRow loadLayout(Connection connection, long paperId) throws Exception {
        try (var statement = connection.prepareStatement("""
                SELECT document_hash, parser_version, layout_confidence, page_count, blocks_json
                FROM paper_layout_artifact WHERE paper_id = ? ORDER BY id DESC LIMIT 1
                """)) {
            statement.setLong(1, paperId);
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).as("paper %s has layout", paperId).isTrue();
                return new LayoutRow(rows.getString("document_hash"), rows.getString("parser_version"),
                        rows.getDouble("layout_confidence"), rows.getInt("page_count"),
                        objectMapper.readValue(rows.getString("blocks_json"), new TypeReference<>() { }));
            }
        }
    }

    private MemoryRow loadMemory(Connection connection, long paperId) throws Exception {
        try (var statement = connection.prepareStatement("""
                SELECT understanding_version, profile_json FROM paper_memory
                WHERE paper_id = ? ORDER BY id DESC LIMIT 1
                """)) {
            statement.setLong(1, paperId);
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).as("paper %s has memory", paperId).isTrue();
                return new MemoryRow(rows.getString("understanding_version"),
                        objectMapper.readTree(rows.getString("profile_json")));
            }
        }
    }

    private EvalSuite readSuite() throws Exception {
        try (InputStream input = getClass().getResourceAsStream(CASES)) {
            assertThat(input).as("eval resource %s", CASES).isNotNull();
            return objectMapper.readValue(input, EvalSuite.class);
        }
    }

    private boolean containsGroups(String value, List<List<String>> groups) {
        String normalized = normalize(value);
        return groups.stream().allMatch(group -> group.stream()
                .map(this::normalize).anyMatch(normalized::contains));
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replace("ﬁ", "fi")
                .replace("ﬂ", "fl")
                .replaceAll("[\\s_{}()]+", "")
                .replace('−', '-');
    }

    private static List<String> stringValues(JsonNode array) {
        List<String> values = new ArrayList<>();
        if (array.isArray()) array.forEach(value -> values.add(value.asText()));
        return values;
    }

    private static String sourceText(DocumentBlock block) {
        if (block.role() == DocumentBlockRole.TABLE && block.tableText() != null) return block.tableText();
        if (block.role() == DocumentBlockRole.FORMULA && block.latex() != null && !block.latex().isBlank()) return block.latex();
        return block.text() == null ? "" : block.text();
    }

    private static void record(boolean passed, Runnable onPass, List<String> failures, String failure) {
        if (passed) onPass.run(); else failures.add(failure);
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private record EvalSuite(String pipelineVersion, List<PaperCase> papers) { }
    private record PaperCase(long paperId, List<RoleCase> roleCases,
                             List<SpanGroup> spanGroups, List<FactCase> facts) { }
    private record RoleCase(String blockId, String expectedRole) { }
    private record SpanGroup(String name, List<String> blockIds) { }
    private record FactCase(String name, List<String> profileFields,
                            List<List<String>> claimTermGroups,
                            List<List<String>> evidenceTermGroups,
                            List<String> requiredEvidenceBlockIds, Integer expectedPage) {
        private FactCase {
            profileFields = profileFields == null ? List.of() : List.copyOf(profileFields);
            claimTermGroups = claimTermGroups == null ? List.of() : List.copyOf(claimTermGroups);
            evidenceTermGroups = evidenceTermGroups == null ? List.of() : List.copyOf(evidenceTermGroups);
            requiredEvidenceBlockIds = requiredEvidenceBlockIds == null
                    ? List.of() : List.copyOf(requiredEvidenceBlockIds);
        }
    }
    private record LayoutRow(String documentHash, String parserVersion, double confidence,
                             int pageCount, List<DocumentBlock> blocks) { }
    private record MemoryRow(String pipelineVersion, JsonNode profile) { }

    private static final class Counters {
        private int coverageTotal;
        private int coveragePassed;
        private int rolesTotal;
        private int rolesPassed;
        private int groupsTotal;
        private int groupsPassed;
        private int factsTotal;
        private int factsPassed;
        private int supportTotal;
        private int supportPassed;
        private int locatorsTotal;
        private int locatorsPassed;

        private void passCoverage() { coveragePassed++; }
        private void passRole() { rolesPassed++; }
        private void passGroup() { groupsPassed++; }
        private void passFact() { factsPassed++; }
        private void passSupport() { supportPassed++; }
        private void passLocator() { locatorsPassed++; }
    }
}
