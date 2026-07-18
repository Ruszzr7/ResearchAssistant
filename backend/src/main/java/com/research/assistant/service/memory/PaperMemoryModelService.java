package com.research.assistant.service.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.common.JsonUtils;
import com.research.assistant.dto.LlmResponse;
import com.research.assistant.service.LLMService;
import com.research.assistant.service.ai.LlmCallPolicy;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Bounded JSON-only model calls for chunk summaries and the global paper profile. */
@Service
public class PaperMemoryModelService {

    static final String CHUNK_PROMPT_VERSION = "paper-memory-chunk-v1";
    static final String PROFILE_PROMPT_VERSION = "paper-memory-profile-v1";

    private static final LlmCallPolicy CHUNK_POLICY = new LlmCallPolicy(
            "paper-memory-chunk", 12_000, 4_096, 2_048, 2, true);
    private static final LlmCallPolicy PROFILE_POLICY = new LlmCallPolicy(
            "paper-memory-profile", 60_000, 16_000, 4_096, 2, true);
    private static final Set<String> CLAIM_CATEGORIES = Set.of(
            "CONTRIBUTION", "METHOD", "FINDING", "LIMITATION", "DEFINITION", "OTHER");

    private static final String CHUNK_SYSTEM_PROMPT = """
            你是严谨的论文分块阅读器。只根据给出的一个论文分块生成结构化记忆，不得调用外部知识，
            不得补写原文没有的信息。每条事实性 claim 必须引用一个或多个输入中真实出现的 block ID。
            不确定的信息留空。只返回一个 JSON 对象，不要 Markdown 或解释。
            """;

    private static final String PROFILE_SYSTEM_PROMPT = """
            你是严谨的论文全局理解器。输入只包含已经逐块核验的结构化摘要；请在这些摘要的证据边界内
            生成论文全局画像。不得引入外部知识，不得把推测写成事实。所有贡献、发现、局限和 benchmark
            必须引用输入中真实存在的 block ID。不确定的信息留空。只返回一个 JSON 对象。
            """;

    private final LLMService llmService;
    private final ObjectMapper objectMapper;

    public PaperMemoryModelService(LLMService llmService, ObjectMapper objectMapper) {
        this.llmService = llmService;
        this.objectMapper = objectMapper;
    }

    public PaperChunkSummary summarize(PaperMemoryChunk chunk) {
        String request = chunkRequest(chunk);
        RuntimeException last = null;
        for (int attempt = 1; attempt <= CHUNK_POLICY.maxAttempts(); attempt++) {
            try {
                String userMessage = attempt == 1 ? request
                        : request + "\n\n上一次输出未通过 JSON 或证据校验。请重新完整输出，尤其不要引用允许列表之外的 block ID。";
                LlmResponse response = llmService.chatWithUsage(
                        CHUNK_SYSTEM_PROMPT, CHUNK_POLICY.limitInput(userMessage), CHUNK_POLICY);
                return parseChunkSummary(chunk, response);
            } catch (RuntimeException exception) {
                last = exception;
            }
        }
        throw new IllegalStateException("论文分块摘要生成失败", last);
    }

    public ProfileGeneration profile(PaperStructure structure,
                                     List<PaperChunkSummary> summaries,
                                     int totalChunks,
                                     int failedChunks) {
        ProfileInput input = profileRequest(structure, summaries);
        RuntimeException last = null;
        for (int attempt = 1; attempt <= PROFILE_POLICY.maxAttempts(); attempt++) {
            try {
                String userMessage = attempt == 1 ? input.prompt()
                        : input.prompt() + "\n\n上一次输出未通过 JSON 或证据校验。请按指定 schema 完整重写。";
                LlmResponse response = llmService.chatWithUsage(
                        PROFILE_SYSTEM_PROMPT, userMessage, PROFILE_POLICY);
                PaperGlobalProfile profile = parseProfile(
                        structure, summaries, totalChunks, failedChunks,
                        input.truncated(), response);
                return new ProfileGeneration(
                        profile,
                        count(response == null ? null : response.getPromptTokens()),
                        count(response == null ? null : response.getCompletionTokens()));
            } catch (RuntimeException exception) {
                last = exception;
            }
        }
        throw new IllegalStateException("论文全局画像生成失败", last);
    }

    private String chunkRequest(PaperMemoryChunk chunk) {
        return """
                promptVersion: %s
                chunkId: %s
                sectionPath: %s
                pages: %d-%d
                allowedBlockIds: %s

                请返回以下 camelCase JSON：
                {
                  "synopsis": "本块的简洁摘要",
                  "claims": [
                    {"category":"CONTRIBUTION|METHOD|FINDING|LIMITATION|DEFINITION|OTHER",
                     "statement":"可核验陈述", "evidenceBlockIds":["block-id"], "confidence":0.0}
                  ],
                  "concepts": [], "datasets": [], "models": [], "metrics": []
                }

                原文块：
                %s
                """.formatted(
                CHUNK_PROMPT_VERSION,
                chunk.id(),
                String.join(" > ", chunk.headingPath()),
                chunk.pageStart(), chunk.pageEnd(),
                String.join(",", chunk.blockIds()),
                chunk.text());
    }

    private PaperChunkSummary parseChunkSummary(PaperMemoryChunk chunk, LlmResponse response) {
        JsonNode root = parseObject(response == null ? "" : response.getContent());
        String synopsis = text(root, "synopsis", 2_400);
        if (synopsis.isBlank()) throw new IllegalArgumentException("分块摘要 synopsis 为空");

        LinkedHashSet<String> issues = new LinkedHashSet<>();
        List<PaperMemoryClaim> claims = claims(
                root.path("claims"), new LinkedHashSet<>(chunk.blockIds()), issues);
        if ("LENGTH".equalsIgnoreCase(response == null ? null : response.getFinishReason())) {
            throw new IllegalArgumentException("分块摘要输出被截断");
        }
        return new PaperChunkSummary(
                chunk.id(), chunk.sourceFingerprint(), chunk.ordinal(), chunk.sectionId(),
                chunk.headingPath(), chunk.pageStart(), chunk.pageEnd(), chunk.blockIds(),
                synopsis, claims,
                strings(root.path("concepts"), 24, 160),
                strings(root.path("datasets"), 24, 160),
                strings(root.path("models"), 24, 160),
                strings(root.path("metrics"), 24, 160),
                PaperChunkSummary.READY, List.copyOf(issues),
                count(response == null ? null : response.getPromptTokens()),
                count(response == null ? null : response.getCompletionTokens()),
                response == null ? "" : response.getFinishReason(), Instant.now());
    }

    private ProfileInput profileRequest(PaperStructure structure,
                                        List<PaperChunkSummary> summaries) {
        List<Map<String, Object>> compact = new ArrayList<>();
        boolean truncated = false;
        int approximateCharacters = 0;
        for (PaperChunkSummary summary : summaries.stream()
                .filter(PaperChunkSummary::ready)
                .sorted(java.util.Comparator.comparingInt(PaperChunkSummary::ordinal))
                .toList()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("chunkId", summary.chunkId());
            item.put("sectionId", summary.sectionId());
            item.put("headingPath", summary.headingPath());
            item.put("pages", summary.pageStart() + "-" + summary.pageEnd());
            item.put("synopsis", limit(summary.synopsis(), 900));
            item.put("claims", summary.claims().stream().limit(6).map(claim -> Map.of(
                    "category", claim.category(),
                    "statement", limit(claim.statement(), 320),
                    "evidenceBlockIds", claim.evidenceBlockIds())).toList());
            item.put("datasets", summary.datasets());
            item.put("models", summary.models());
            item.put("metrics", summary.metrics());
            String encoded = write(item);
            if (approximateCharacters + encoded.length() > 48_000) {
                truncated = true;
                continue;
            }
            compact.add(item);
            approximateCharacters += encoded.length();
        }
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("promptVersion", PROFILE_PROMPT_VERSION);
        source.put("paperId", structure.paperId());
        source.put("title", limit(structure.metadata().title(), 500));
        source.put("metadata", Map.of(
                "authors", structure.metadata().authors().stream().limit(30).toList(),
                "year", structure.metadata().year() == null ? "" : structure.metadata().year(),
                "source", limit(structure.metadata().source(), 500),
                "doi", limit(structure.metadata().doi(), 200),
                "keywords", structure.metadata().keywords().stream().limit(40).toList(),
                "abstractText", limit(structure.metadata().abstractText(), 4_000)));
        source.put("chunkSummaries", compact);

        String promptTemplate = """
                请基于下面的分块摘要生成 camelCase JSON：
                {
                  "domain":"", "researchProblem":"",
                  "coreContributions":[{"category":"CONTRIBUTION","statement":"","evidenceBlockIds":[],"confidence":0.0}],
                  "methodType":"THEORETICAL|EXPERIMENTAL|SYSTEM|SURVEY|OTHER",
                  "methodSummary":"", "datasets":[], "models":[], "metrics":[],
                  "keyFindings":[{"category":"FINDING","statement":"","evidenceBlockIds":[],"confidence":0.0}],
                  "limitations":[{"category":"LIMITATION","statement":"","evidenceBlockIds":[],"confidence":0.0}],
                  "experimentSetup":{"taskDefinition":"","dataSplit":"","baselines":"","implementation":""},
                  "benchmarkResults":[{"metric":"","value":"","baseline":"","dataset":"","evidenceBlockIds":[]}],
                  "sectionDigests":[{"sectionId":"","headingPath":[],"summary":"","sourceChunkIds":[]}],
                  "openQuestions":[]
                }

                输入：
                %s
                """;
        String prompt = promptTemplate.formatted(write(source));
        while (prompt.length() > PROFILE_POLICY.maxInputChars() && !compact.isEmpty()) {
            compact.remove(compact.size() - 1);
            truncated = true;
            prompt = promptTemplate.formatted(write(source));
        }
        if (prompt.length() > PROFILE_POLICY.maxInputChars()) {
            throw new IllegalStateException("论文元数据超过全局画像输入上限");
        }
        return new ProfileInput(prompt, truncated);
    }

    private PaperGlobalProfile parseProfile(PaperStructure structure,
                                            List<PaperChunkSummary> summaries,
                                            int totalChunks,
                                            int failedChunks,
                                            boolean inputTruncated,
                                            LlmResponse response) {
        if ("LENGTH".equalsIgnoreCase(response == null ? null : response.getFinishReason())) {
            throw new IllegalArgumentException("全局画像输出被截断");
        }
        JsonNode root = parseObject(response == null ? "" : response.getContent());
        Set<String> allowedBlocks = new LinkedHashSet<>();
        Set<String> allowedChunks = new LinkedHashSet<>();
        for (PaperChunkSummary summary : summaries) {
            if (!summary.ready()) continue;
            allowedBlocks.addAll(summary.blockIds());
            allowedChunks.add(summary.chunkId());
        }
        LinkedHashSet<String> issues = new LinkedHashSet<>();
        if (inputTruncated) issues.add("PROFILE_INPUT_TRUNCATED");
        List<PaperMemoryClaim> contributions = claims(
                root.path("coreContributions"), allowedBlocks, issues);
        List<PaperMemoryClaim> findings = claims(root.path("keyFindings"), allowedBlocks, issues);
        List<PaperMemoryClaim> limitations = claims(root.path("limitations"), allowedBlocks, issues);
        String researchProblem = text(root, "researchProblem", 2_400);
        String methodSummary = text(root, "methodSummary", 4_000);
        if (researchProblem.isBlank() && contributions.isEmpty() && methodSummary.isBlank()) {
            throw new IllegalArgumentException("全局画像缺少可用的核心内容");
        }
        int readyCount = (int) summaries.stream().filter(PaperChunkSummary::ready).count();
        boolean complete = failedChunks == 0 && readyCount == totalChunks && !inputTruncated;
        return new PaperGlobalProfile(
                PaperGlobalProfile.SCHEMA_VERSION,
                structure.paperId(), structure.metadata().title(),
                text(root, "domain", 160), researchProblem,
                contributions,
                methodType(text(root, "methodType", 48)), methodSummary,
                strings(root.path("datasets"), 64, 200),
                strings(root.path("models"), 64, 200),
                strings(root.path("metrics"), 64, 200),
                findings, limitations,
                stringMap(root.path("experimentSetup"), 16, 1_000),
                benchmarks(root.path("benchmarkResults"), allowedBlocks, issues),
                sectionDigests(root.path("sectionDigests"), allowedChunks, issues),
                strings(root.path("openQuestions"), 32, 600),
                new PaperGlobalProfile.Coverage(totalChunks, readyCount, failedChunks, complete),
                List.copyOf(issues), Instant.now());
    }

    private List<PaperMemoryClaim> claims(JsonNode node,
                                          Set<String> allowedBlocks,
                                          Set<String> issues) {
        if (!node.isArray()) return List.of();
        List<PaperMemoryClaim> result = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isObject() || result.size() >= 80) break;
            String statement = text(item, "statement", 1_200);
            if (statement.isBlank()) continue;
            List<String> evidence = allowedStrings(item.path("evidenceBlockIds"), allowedBlocks, 16);
            if (evidence.isEmpty()) {
                issues.add("UNGROUNDED_CLAIM_DROPPED");
                continue;
            }
            String category = text(item, "category", 32).toUpperCase(Locale.ROOT);
            if (!CLAIM_CATEGORIES.contains(category)) category = "OTHER";
            result.add(new PaperMemoryClaim(
                    category, statement, evidence, item.path("confidence").asDouble(0.7)));
        }
        return List.copyOf(result);
    }

    private List<PaperGlobalProfile.BenchmarkResult> benchmarks(
            JsonNode node, Set<String> allowedBlocks, Set<String> issues) {
        if (!node.isArray()) return List.of();
        List<PaperGlobalProfile.BenchmarkResult> result = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isObject() || result.size() >= 64) break;
            List<String> evidence = allowedStrings(item.path("evidenceBlockIds"), allowedBlocks, 16);
            if (evidence.isEmpty()) {
                issues.add("UNGROUNDED_BENCHMARK_DROPPED");
                continue;
            }
            result.add(new PaperGlobalProfile.BenchmarkResult(
                    text(item, "metric", 200), text(item, "value", 200),
                    text(item, "baseline", 300), text(item, "dataset", 200), evidence));
        }
        return List.copyOf(result);
    }

    private List<PaperGlobalProfile.SectionDigest> sectionDigests(
            JsonNode node, Set<String> allowedChunks, Set<String> issues) {
        if (!node.isArray()) return List.of();
        List<PaperGlobalProfile.SectionDigest> result = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isObject() || result.size() >= 128) break;
            List<String> sourceChunks = allowedStrings(
                    item.path("sourceChunkIds"), allowedChunks, 32);
            if (sourceChunks.isEmpty()) {
                issues.add("UNSOURCED_SECTION_DIGEST_DROPPED");
                continue;
            }
            result.add(new PaperGlobalProfile.SectionDigest(
                    text(item, "sectionId", 128),
                    strings(item.path("headingPath"), 12, 240),
                    text(item, "summary", 1_500), sourceChunks));
        }
        return List.copyOf(result);
    }

    private JsonNode parseObject(String output) {
        try {
            JsonNode root = objectMapper.readTree(JsonUtils.extractJson(output));
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("模型输出不是 JSON 对象");
            }
            return root;
        } catch (Exception exception) {
            throw new IllegalArgumentException("模型输出 JSON 无法读取", exception);
        }
    }

    private List<String> allowedStrings(JsonNode node, Set<String> allowed, int maxItems) {
        List<String> values = strings(node, maxItems * 2, 256);
        return values.stream().filter(allowed::contains).distinct().limit(maxItems).toList();
    }

    private List<String> strings(JsonNode node, int maxItems, int maxLength) {
        if (!node.isArray()) return List.of();
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (JsonNode item : node) {
            String value = item.asText("").trim();
            if (!value.isBlank()) result.add(limit(value, maxLength));
            if (result.size() >= maxItems) break;
        }
        return List.copyOf(result);
    }

    private Map<String, String> stringMap(JsonNode node, int maxItems, int maxLength) {
        if (!node.isObject()) return Map.of();
        Map<String, String> result = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> {
            if (result.size() >= maxItems) return;
            String value = entry.getValue().asText("").trim();
            if (!value.isBlank()) result.put(limit(entry.getKey(), 80), limit(value, maxLength));
        });
        return Map.copyOf(result);
    }

    private String text(JsonNode node, String field, int maxLength) {
        return limit(node.path(field).asText("").trim(), maxLength);
    }

    private String methodType(String value) {
        String normalized = value == null ? "" : value.toUpperCase(Locale.ROOT);
        return Set.of("THEORETICAL", "EXPERIMENTAL", "SYSTEM", "SURVEY", "OTHER")
                .contains(normalized) ? normalized : "OTHER";
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("论文记忆 prompt 无法序列化", exception);
        }
    }

    private String limit(String value, int maxLength) {
        if (value == null) return "";
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private int count(Integer value) {
        return value == null ? 0 : Math.max(0, value);
    }

    private record ProfileInput(String prompt, boolean truncated) { }

    public record ProfileGeneration(PaperGlobalProfile profile,
                                    int promptTokens,
                                    int completionTokens) { }
}
