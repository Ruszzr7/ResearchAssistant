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
                "仅当当前对话缺少关于研究问题、贡献、方法、实验、发现或局限的全文方向时，加载当前论文的已生成画像。无关问题、局部事实、精确引文、引用或页面操作不要调用。画像是参考上下文，不是可直接引用的证据，也不能证明某内容未在论文中出现；涉及论文是否讨论、比较或包含某内容时必须继续使用 paper-evidence 检索原文。只有上下文缺失或被压缩后才再次调用。返回的 claimRef 可以帮助后续证据检索。",
                EMPTY_SCHEMA);
    }

    public AgentToolExecution execute(long paperId, PaperSourceCatalog catalog) {
        PaperMemoryRecord memory = memoryMapper.selectLatest(paperId);
        if (memory == null || memory.getProfileJson() == null || memory.getProfileJson().isBlank()) {
            return result(Map.of(
                    "status", "unavailable",
                    "message", "已生成的论文画像不可用。原文可用时请使用论文证据，否则只能依据其他有效上下文回答。"));
        }
        if (catalog == null || !catalog.documentHash().equals(memory.getDocumentHash())
                || !catalog.parserVersion().equals(memory.getLayoutParserVersion())) {
            return result(Map.of(
                    "status", "stale",
                    "message", "已生成的论文画像属于其他论文版本，不得使用。"));
        }
        try {
            if (memory.getProfileQualityJson() == null || memory.getProfileQualityJson().isBlank()
                    || !objectMapper.readTree(memory.getProfileQualityJson()).path("usable").asBoolean(false)) {
                return result(Map.of(
                        "status", "unavailable",
                        "message", "已生成的论文画像未通过最低身份和内容检查。请使用论文原文证据或其他有效上下文。"));
            }
            PaperGlobalProfile profile = objectMapper.readValue(memory.getProfileJson(), PaperGlobalProfile.class);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("status", "READY".equalsIgnoreCase(memory.getStatus()) ? "ready" : "partial");
            payload.put("untrustedPaperContent", true);
            payload.put("paperId", paperId);
            payload.put("profile", compactProfile(profile, catalog));
            payload.put("sourceObjectIds", profileSourceIds(profile, catalog));
            payload.put("usage", "使用该画像建立全文方向。只有 sourceObjectIds 直接支持某项陈述时才可以引用。画像不能证明某内容未出现；涉及论文是否讨论、比较或包含某内容，以及精确引文、公式、图、表或页面级视觉检查时，请使用 paper-evidence 检索原文。不要为了确认上下文中已有的信息再次调用本 Skill；只有其说明或画像上下文在压缩后缺失时才重新激活。");
            return result(payload, profileSourceIds(profile, catalog));
        } catch (Exception error) {
            throw new IllegalStateException("已保存的论文画像格式无效", error);
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
                throw new IllegalStateException("论文画像超过模型负载上限");
            }
            return new AgentToolExecution(json, sourceObjectIds);
        } catch (Exception error) {
            throw error instanceof RuntimeException runtime ? runtime
                    : new IllegalStateException("论文画像序列化失败", error);
        }
    }
}
