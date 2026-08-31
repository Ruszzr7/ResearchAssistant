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
import java.util.Map;
import java.util.Set;
import java.util.List;
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
            payload.put("profile", compactProfile(profile));
            payload.put("usage", "Use this overview for whole-paper orientation. For exact support or a clickable citation, use a relevant claimRef to guide retrieve_paper_evidence. Do not call this tool again merely to confirm information already present in context.");
            return result(payload);
        } catch (Exception error) {
            throw new IllegalStateException("stored paper overview is invalid", error);
        }
    }

    private Map<String, Object> compactProfile(PaperGlobalProfile profile) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("title", profile.title());
        value.put("domain", profile.domain());
        value.put("researchProblem", profile.researchProblem());
        value.put("coreContributions", claims("contribution", profile.coreContributions()));
        value.put("methodType", profile.methodType());
        value.put("methodSummary", profile.methodSummary());
        value.put("datasets", profile.datasets());
        value.put("models", profile.models());
        value.put("metrics", profile.metrics());
        value.put("keyFindings", claims("finding", profile.keyFindings()));
        value.put("limitations", claims("limitation", profile.limitations()));
        value.put("experimentSetup", profile.experimentSetup());
        value.put("benchmarkResults", IntStream.range(0, profile.benchmarkResults().size()).mapToObj(index -> {
            PaperGlobalProfile.BenchmarkResult result = profile.benchmarkResults().get(index);
            return Map.of("claimRef", "benchmark:" + index,
                    "metric", result.metric(), "value", result.value(),
                    "baseline", result.baseline(), "dataset", result.dataset());
        }).toList());
        value.put("openQuestions", profile.openQuestions());
        return value;
    }

    private List<Map<String, String>> claims(String prefix, List<PaperMemoryClaim> claims) {
        return IntStream.range(0, claims.size())
                .mapToObj(index -> Map.of("claimRef", prefix + ":" + index,
                        "statement", claims.get(index).statement()))
                .toList();
    }

    private AgentToolExecution result(Object value) {
        try {
            String json = objectMapper.writeValueAsString(value);
            if (json.getBytes(StandardCharsets.UTF_8).length > MAX_RESULT_BYTES) {
                throw new IllegalStateException("paper overview exceeded the model payload limit");
            }
            return new AgentToolExecution(json, Set.of());
        } catch (Exception error) {
            throw error instanceof RuntimeException runtime ? runtime
                    : new IllegalStateException("failed to serialize paper overview", error);
        }
    }
}
