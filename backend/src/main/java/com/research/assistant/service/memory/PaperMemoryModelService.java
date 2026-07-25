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
import java.util.function.Function;

/** Bounded JSON-only model calls for chunk summaries and the global paper profile. */
@Service
public class PaperMemoryModelService {

    static final String WHOLE_PROMPT_VERSION = "paper-memory-whole-v2";
    static final String CHUNK_PROMPT_VERSION = "paper-memory-chunk-v2";
    static final String PROFILE_PROMPT_VERSION = "paper-memory-profile-v2";

    private static final LlmCallPolicy WHOLE_POLICY = new LlmCallPolicy(
            "paper-memory-whole", 100_000, 24_000, 1_600, 1, true);
    private static final LlmCallPolicy CHUNK_POLICY = new LlmCallPolicy(
            "paper-memory-chunk", 48_000, 12_000, 900, 1, true);
    private static final LlmCallPolicy PROFILE_POLICY = new LlmCallPolicy(
            "paper-memory-profile", 36_000, 8_000, 1_600, 1, true);
    private static final LlmCallPolicy REPAIR_POLICY = new LlmCallPolicy(
            "paper-memory-json-repair", 8_000, 2_048, 300, 1, true);
    private static final Set<String> CLAIM_CATEGORIES = Set.of(
            "CONTRIBUTION", "METHOD", "FINDING", "LIMITATION", "DEFINITION", "OTHER");

    private static final String CHUNK_SYSTEM_PROMPT = """
            你是严谨的论文分块阅读器。只根据给出的一个论文分块生成结构化记忆，不得调用外部知识，
            不得补写原文没有的信息。每条事实性 claim 必须引用一个或多个输入中真实出现的 block ID。
            不确定的信息留空。只返回一个紧凑 JSON 对象，不要 Markdown、解释或推理过程。
            synopsis 不超过 180 个汉字；claims 最多 5 条，每条 statement 不超过 80 个汉字；
            concepts、datasets、models、metrics 各最多 5 项。
            """;

    private static final String PROFILE_SYSTEM_PROMPT = """
            你是严谨的论文全局理解器。只在输入证据边界内生成紧凑论文画像，不得引入外部知识，
            不得把推测写成事实。事实必须引用输入中真实存在的 block ID。不确定的信息留空。
            只返回 JSON，不要 Markdown、解释或推理过程。researchProblem 和 methodSummary 各不超过
            250 个汉字；贡献、发现各最多 5 条，局限最多 3 条；每条不超过 80 个汉字且最多引用 3 个 block ID；
            datasets、models、metrics、openQuestions 各最多 5 项；sectionDigests 最多 8 项。
            """;

    private final LLMService llmService;
    private final ObjectMapper objectMapper;

    public PaperMemoryModelService(LLMService llmService, ObjectMapper objectMapper) {
        this.llmService = llmService;
        this.objectMapper = objectMapper;
    }

    public PaperChunkSummary summarize(PaperMemoryChunk chunk) {
        Generated<PaperChunkSummary> generated = generate(
                CHUNK_SYSTEM_PROMPT,
                CHUNK_POLICY.limitInput(chunkRequest(chunk)),
                CHUNK_POLICY,
                response -> parseChunkSummary(chunk, response),
                chunkSchema());
        return withUsage(generated.value(), generated);
    }

    public ProfileGeneration profile(PaperStructure structure,
                                     List<PaperChunkSummary> summaries,
                                     int totalChunks,
                                     int failedChunks) {
        ProfileInput input = profileRequest(structure, summaries);
        Generated<PaperGlobalProfile> generated = generate(
                PROFILE_SYSTEM_PROMPT,
                input.prompt(),
                PROFILE_POLICY,
                response -> parseProfile(
                        structure, summaries, totalChunks, failedChunks,
                        input.truncated(), response),
                profileSchema());
        return new ProfileGeneration(
                generated.value(), generated.promptTokens(), generated.completionTokens());
    }

    public WholePaperGeneration understandWhole(PaperStructure structure,
                                                PaperMemoryChunk chunk) {
        PaperChunkSummary source = sourceSummary(chunk);
        Generated<PaperGlobalProfile> generated = generate(
                PROFILE_SYSTEM_PROMPT,
                wholePaperRequest(structure, chunk),
                WHOLE_POLICY,
                response -> parseProfile(
                        structure, List.of(source), 1, 0, false, response),
                profileSchema());
        PaperChunkSummary summary = summaryFromProfile(chunk, generated.value(), generated);
        return new WholePaperGeneration(generated.value(), summary);
    }

    private String chunkRequest(PaperMemoryChunk chunk) {
        return """
                promptVersion: %s
                chunkId: %s
                sectionPath: %s
                pages: %d-%d
                allowedBlockIds: %s

                请按以下 schema 返回，严格遵守数量和长度限制：
                %s

                原文块：
                %s
                """.formatted(
                CHUNK_PROMPT_VERSION,
                chunk.id(),
                String.join(" > ", chunk.headingPath()),
                chunk.pageStart(), chunk.pageEnd(),
                String.join(",", chunk.blockIds()),
                chunkSchema(),
                chunk.text());
    }

    private String wholePaperRequest(PaperStructure structure, PaperMemoryChunk chunk) {
        String prompt = """
                promptVersion: %s
                paperId: %d
                title: %s
                allowedBlockIds: %s

                请阅读全文后按以下 schema 生成论文画像。所有章节均已包含，不要逐段复述：
                %s

                论文原文：
                %s
                """.formatted(
                WHOLE_PROMPT_VERSION,
                structure.paperId(),
                limit(structure.metadata().title(), 500),
                String.join(",", chunk.blockIds()),
                profileSchema(),
                chunk.text());
        if (WHOLE_POLICY.exceedsInputBudget(PROFILE_SYSTEM_PROMPT, prompt)) {
            throw new IllegalArgumentException("论文正文超过单次理解 Token 预算");
        }
        return WHOLE_POLICY.limitInput(prompt);
    }

    private String chunkSchema() {
        return """
                {
                  "synopsis":"不超过180个汉字",
                  "claims":[
                    {"category":"CONTRIBUTION|METHOD|FINDING|LIMITATION|DEFINITION|OTHER",
                     "statement":"不超过80个汉字","evidenceBlockIds":["最多3个block-id"],"confidence":0.0}
                  ],
                  "concepts":[],"datasets":[],"models":[],"metrics":[]
                }
                claims 最多5条；其余数组各最多5项。""";
    }

    private String profileSchema() {
        return """
                {
                  "domain":"",
                  "researchProblem":"不超过250个汉字",
                  "coreContributions":[
                    {"category":"CONTRIBUTION","statement":"不超过80个汉字",
                     "evidenceBlockIds":["最多3个block-id"],"confidence":0.0}
                  ],
                  "methodType":"THEORETICAL|EXPERIMENTAL|SYSTEM|SURVEY|OTHER",
                  "methodSummary":"不超过250个汉字",
                  "datasets":[],"models":[],"metrics":[],
                  "keyFindings":[],
                  "limitations":[],
                  "experimentSetup":{"taskDefinition":"","baselines":""},
                  "benchmarkResults":[],
                  "sectionDigests":[],
                  "openQuestions":[]
                }
                贡献和发现各最多5条，局限最多3条；其余数组各最多5项，sectionDigests最多8项。""";
    }

    private PaperChunkSummary parseChunkSummary(PaperMemoryChunk chunk, LlmResponse response) {
        JsonNode root = parseObject(response == null ? "" : response.getContent());
        String synopsis = text(root, "synopsis", 600);
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
                strings(root.path("concepts"), 5, 160),
                strings(root.path("datasets"), 5, 160),
                strings(root.path("models"), 5, 160),
                strings(root.path("metrics"), 5, 160),
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
            item.put("synopsis", limit(summary.synopsis(), 500));
            item.put("claims", summary.claims().stream().limit(5).map(claim -> Map.of(
                    "category", claim.category(),
                    "statement", limit(claim.statement(), 160),
                    "evidenceBlockIds", claim.evidenceBlockIds().stream().limit(3).toList())).toList());
            item.put("datasets", summary.datasets().stream().limit(5).toList());
            item.put("models", summary.models().stream().limit(5).toList());
            item.put("metrics", summary.metrics().stream().limit(5).toList());
            String encoded = write(item);
            if (approximateCharacters + encoded.length() > 28_000) {
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
                请基于下面的分块摘要生成论文画像，严格遵守数量和长度限制：
                %s

                输入：
                %s
                """;
        String prompt = promptTemplate.formatted(profileSchema(), write(source));
        while (prompt.length() > PROFILE_POLICY.maxInputChars() && !compact.isEmpty()) {
            compact.remove(compact.size() - 1);
            truncated = true;
            prompt = promptTemplate.formatted(profileSchema(), write(source));
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
        String researchProblem = text(root, "researchProblem", 800);
        String methodSummary = text(root, "methodSummary", 800);
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
                strings(root.path("datasets"), 5, 200),
                strings(root.path("models"), 5, 200),
                strings(root.path("metrics"), 5, 200),
                findings, limitations,
                stringMap(root.path("experimentSetup"), 4, 500),
                benchmarks(root.path("benchmarkResults"), allowedBlocks, issues),
                sectionDigests(root.path("sectionDigests"), allowedChunks, issues),
                strings(root.path("openQuestions"), 5, 300),
                new PaperGlobalProfile.Coverage(totalChunks, readyCount, failedChunks, complete),
                List.copyOf(issues), Instant.now());
    }

    private List<PaperMemoryClaim> claims(JsonNode node,
                                          Set<String> allowedBlocks,
                                          Set<String> issues) {
        if (!node.isArray()) return List.of();
        List<PaperMemoryClaim> result = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isObject() || result.size() >= 5) break;
            String statement = text(item, "statement", 320);
            if (statement.isBlank()) continue;
            List<String> evidence = allowedStrings(item.path("evidenceBlockIds"), allowedBlocks, 3);
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
            if (!item.isObject() || result.size() >= 5) break;
            List<String> evidence = allowedStrings(item.path("evidenceBlockIds"), allowedBlocks, 3);
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
            if (!item.isObject() || result.size() >= 8) break;
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

    private <T> Generated<T> generate(String systemPrompt,
                                      String userMessage,
                                      LlmCallPolicy policy,
                                      Function<LlmResponse, T> parser,
                                      String schema) {
        LlmResponse primary;
        try {
            primary = llmService.chatWithUsage(systemPrompt, userMessage, policy);
        } catch (RuntimeException exception) {
            throw new PaperMemoryGenerationException(
                    "论文理解模型调用失败", exception, 0, 0, "");
        }
        int promptTokens = count(primary.getPromptTokens());
        int completionTokens = count(primary.getCompletionTokens());
        try {
            return new Generated<>(
                    parser.apply(primary), promptTokens, completionTokens, primary.getFinishReason());
        } catch (RuntimeException parseFailure) {
            if ("LENGTH".equalsIgnoreCase(primary.getFinishReason())) {
                throw new PaperMemoryGenerationException(
                        "论文理解输出达到预算上限", parseFailure,
                        promptTokens, completionTokens, primary.getFinishReason());
            }
            String repairPrompt = """
                    请仅修复下面输出的 JSON 语法和 schema，不得增加新事实，不得扩写。
                    只返回修复后的紧凑 JSON。

                    schema:
                    %s

                    原输出:
                    %s
                    """.formatted(schema, limit(primary.getContent(), 6_000));
            LlmResponse repaired;
            try {
                repaired = llmService.chatWithUsage(
                        "你是 JSON 修复器。不要解释，不要推理，不得添加输入中不存在的信息。",
                        REPAIR_POLICY.limitInput(repairPrompt),
                        REPAIR_POLICY);
            } catch (RuntimeException repairFailure) {
                throw new PaperMemoryGenerationException(
                        "论文理解 JSON 修复调用失败", repairFailure,
                        promptTokens, completionTokens, primary.getFinishReason());
            }
            promptTokens += count(repaired.getPromptTokens());
            completionTokens += count(repaired.getCompletionTokens());
            try {
                return new Generated<>(
                        parser.apply(repaired), promptTokens, completionTokens,
                        repaired.getFinishReason());
            } catch (RuntimeException repairParseFailure) {
                throw new PaperMemoryGenerationException(
                        "论文理解输出无法修复", repairParseFailure,
                        promptTokens, completionTokens, repaired.getFinishReason());
            }
        }
    }

    private PaperChunkSummary withUsage(PaperChunkSummary summary,
                                        Generated<?> generated) {
        return new PaperChunkSummary(
                summary.chunkId(), summary.sourceFingerprint(), summary.ordinal(),
                summary.sectionId(), summary.headingPath(), summary.pageStart(), summary.pageEnd(),
                summary.blockIds(), summary.synopsis(), summary.claims(), summary.concepts(),
                summary.datasets(), summary.models(), summary.metrics(), summary.status(),
                summary.qualityIssues(), generated.promptTokens(), generated.completionTokens(),
                generated.finishReason(), summary.generatedAt());
    }

    private PaperChunkSummary sourceSummary(PaperMemoryChunk chunk) {
        return new PaperChunkSummary(
                chunk.id(), chunk.sourceFingerprint(), chunk.ordinal(), chunk.sectionId(),
                chunk.headingPath(), chunk.pageStart(), chunk.pageEnd(), chunk.blockIds(),
                "whole paper source", List.of(), List.of(), List.of(), List.of(), List.of(),
                PaperChunkSummary.READY, List.of(), 0, 0, "", Instant.now());
    }

    private PaperChunkSummary summaryFromProfile(PaperMemoryChunk chunk,
                                                 PaperGlobalProfile profile,
                                                 Generated<?> generated) {
        List<PaperMemoryClaim> claims = new ArrayList<>();
        claims.addAll(profile.coreContributions());
        claims.addAll(profile.keyFindings());
        claims.addAll(profile.limitations());
        String synopsis = firstNonBlank(
                profile.researchProblem(),
                profile.methodSummary(),
                structureSynopsis(profile));
        return new PaperChunkSummary(
                chunk.id(), chunk.sourceFingerprint(), chunk.ordinal(), chunk.sectionId(),
                chunk.headingPath(), chunk.pageStart(), chunk.pageEnd(), chunk.blockIds(),
                limit(synopsis, 600), claims.stream().limit(13).toList(),
                List.of(), profile.datasets(), profile.models(), profile.metrics(),
                PaperChunkSummary.READY, profile.qualityIssues(),
                generated.promptTokens(), generated.completionTokens(),
                generated.finishReason(), Instant.now());
    }

    private String structureSynopsis(PaperGlobalProfile profile) {
        return profile.title() + " — " + profile.domain();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value.strip();
        }
        return "";
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

    private record Generated<T>(T value,
                                int promptTokens,
                                int completionTokens,
                                String finishReason) {
        private Generated {
            promptTokens = Math.max(0, promptTokens);
            completionTokens = Math.max(0, completionTokens);
            finishReason = finishReason == null ? "" : finishReason;
        }
    }

    public record ProfileGeneration(PaperGlobalProfile profile,
                                    int promptTokens,
                                    int completionTokens) { }

    public record WholePaperGeneration(PaperGlobalProfile profile,
                                       PaperChunkSummary summary) { }
}
