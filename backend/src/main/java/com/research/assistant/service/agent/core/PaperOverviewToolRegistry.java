package com.research.assistant.service.agent.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.entity.PaperMemoryRecord;
import com.research.assistant.mapper.PaperMemoryMapper;
import com.research.assistant.service.agent.source.PaperSourceCatalog;
import com.research.assistant.service.memory.PaperGlobalProfile;
import com.research.assistant.service.memory.PaperMemoryClaim;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.List;
import java.util.Arrays;
import java.util.stream.IntStream;

/** Loads the versioned paper portrait on demand. It never performs retrieval or model calls. */
@Service
public class PaperOverviewToolRegistry {

    public static final String TOOL_NAME = "read_paper_profile";
    static final int MAX_RESULT_BYTES = 24 * 1024;
    private static final String EMPTY_SCHEMA = """
            {"type":"object","properties":{},"additionalProperties":false}
            """;

    private final PaperMemoryMapper memoryMapper;
    private final ObjectMapper objectMapper;

    public PaperOverviewToolRegistry(PaperMemoryMapper memoryMapper, ObjectMapper objectMapper) {
        this.memoryMapper = memoryMapper;
        this.objectMapper = objectMapper;
    }

    public AgentToolDefinition definition() {
        return new AgentToolDefinition(TOOL_NAME,
                "Load the current paper's prepared overview only when the conversation lacks whole-paper orientation about the research problem, contributions, method, experiments, findings, or limitations. Do not call it for unrelated questions, local facts, exact quotations, citations, or page operations. The overview is reference context rather than directly citable evidence; call it again only when that context is absent or was compacted. A returned claimRef can guide a later evidence search.",
                EMPTY_SCHEMA);
    }

    public AgentToolExecution execute(long paperId, PaperSourceCatalog catalog) {
        PaperMemoryRecord memory = memoryMapper.selectLatest(paperId);
        if (memory == null || memory.getProfileJson() == null || memory.getProfileJson().isBlank()) {
            return result(Map.of(
                    "status", "unavailable",
                    "message", "The prepared paper overview is unavailable. Use paper evidence when original text is available, or answer only from other valid context."));
        }
        if (catalog == null || !catalog.documentHash().equals(memory.getDocumentHash())
                || !catalog.parserVersion().equals(memory.getLayoutParserVersion())) {
            return result(Map.of(
                    "status", "stale",
                    "message", "The prepared overview belongs to another paper version and must not be used."));
        }
        try {
            if (memory.getProfileQualityJson() == null || memory.getProfileQualityJson().isBlank()
                    || !objectMapper.readTree(memory.getProfileQualityJson()).path("usable").asBoolean(false)) {
                return result(Map.of(
                        "status", "unavailable",
                        "message", "The prepared overview did not pass the minimum identity and content checks. Use original paper evidence or other valid context."));
            }
            PaperGlobalProfile profile = objectMapper.readValue(memory.getProfileJson(), PaperGlobalProfile.class);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("status", "READY".equalsIgnoreCase(memory.getStatus()) ? "ready" : "partial");
            payload.put("untrustedPaperContent", true);
            payload.put("paperId", paperId);
            payload.put("profile", compactProfile(profile, catalog));
            payload.put("sourceObjectIds", profileSourceIds(profile, catalog));
            payload.put("usage", "Use this overview for whole-paper orientation. Its sourceObjectIds may be cited when they directly support a claim. For exact quotations, formulas, figures, tables, or page-specific visual inspection, use paper-evidence. Do not call this Skill again merely to confirm information already present in context; reactivate it only when its instructions or profile context are absent after compaction.");
            return result(payload, profileSourceIds(profile, catalog));
        } catch (Exception error) {
            throw new IllegalStateException("stored paper overview is invalid", error);
        }
    }

    private Map<String, Object> compactProfile(PaperGlobalProfile profile, PaperSourceCatalog catalog) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("title", profile.title());
        value.put("domain", profile.domain());
        value.put("researchProblem", profile.researchProblem());
        value.put("coreContributions", claims("contribution", profile.coreContributions(), catalog));
        value.put("methodType", profile.methodType());
        value.put("methodSummary", profile.methodSummary());
        value.put("datasets", profile.datasets());
        value.put("models", profile.models());
        value.put("metrics", profile.metrics());
        value.put("keyFindings", claims("finding", profile.keyFindings(), catalog));
        value.put("limitations", claims("limitation", profile.limitations(), catalog));
        value.put("experimentSetup", profile.experimentSetup());
        value.put("benchmarkResults", IntStream.range(0, profile.benchmarkResults().size()).mapToObj(index -> {
            PaperGlobalProfile.BenchmarkResult result = profile.benchmarkResults().get(index);
            return Map.of("claimRef", "benchmark:" + index,
                    "metric", result.metric(), "value", result.value(),
                    "baseline", result.baseline(), "dataset", result.dataset(),
                    "sourceObjectIds", validSourceIds(result.evidenceBlockIds(), catalog));
        }).toList());
        value.put("openQuestions", profile.openQuestions());
        return value;
    }

    private List<Map<String, Object>> claims(String prefix, List<PaperMemoryClaim> claims,
                                             PaperSourceCatalog catalog) {
        return IntStream.range(0, claims.size())
                .mapToObj(index -> Map.of("claimRef", prefix + ":" + index,
                        "statement", claims.get(index).statement(),
                        "sourceObjectIds", validSourceIds(claims.get(index).evidenceBlockIds(), catalog)))
                .toList();
    }

    private Set<String> profileSourceIds(PaperGlobalProfile profile, PaperSourceCatalog catalog) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        profile.coreContributions().forEach(claim -> result.addAll(validSourceIds(claim.evidenceBlockIds(), catalog)));
        profile.keyFindings().forEach(claim -> result.addAll(validSourceIds(claim.evidenceBlockIds(), catalog)));
        profile.limitations().forEach(claim -> result.addAll(validSourceIds(claim.evidenceBlockIds(), catalog)));
        profile.benchmarkResults().forEach(benchmark -> result.addAll(validSourceIds(benchmark.evidenceBlockIds(), catalog)));
        return Set.copyOf(result);
    }

    private List<String> validSourceIds(List<String> sourceIds, PaperSourceCatalog catalog) {
        if (sourceIds == null || sourceIds.isEmpty() || catalog == null) return List.of();
        Set<String> requested = sourceIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String requestedId : requested) {
            if (catalog.objects().containsKey(requestedId)) result.add(requestedId);
        }
        for (var source : catalog.objects().values()) {
            Set<String> blockIds = new LinkedHashSet<>();
            String many = source.provenance().getOrDefault("blockIds", "");
            if (!many.isBlank()) Arrays.stream(many.split(","))
                    .map(String::trim).filter(value -> !value.isBlank()).forEach(blockIds::add);
            String one = source.provenance().getOrDefault("blockId", "");
            if (!one.isBlank()) blockIds.add(one.trim());
            if (!java.util.Collections.disjoint(blockIds, requested)) result.add(source.sourceObjectId());
        }
        return List.copyOf(result);
    }

    private AgentToolExecution result(Object value) {
        return result(value, Set.of());
    }

    private AgentToolExecution result(Object value, Set<String> sourceObjectIds) {
        try {
            String json = objectMapper.writeValueAsString(value);
            if (json.getBytes(StandardCharsets.UTF_8).length > MAX_RESULT_BYTES) {
                throw new IllegalStateException("paper overview exceeded the model payload limit");
            }
            return new AgentToolExecution(json, sourceObjectIds);
        } catch (Exception error) {
            throw error instanceof RuntimeException runtime ? runtime
                    : new IllegalStateException("failed to serialize paper overview", error);
        }
    }
}
