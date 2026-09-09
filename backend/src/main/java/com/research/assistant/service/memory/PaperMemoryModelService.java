package com.research.assistant.service.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.common.JsonUtils;
import com.research.assistant.dto.LlmResponse;
import com.research.assistant.service.ai.LlmCallPolicy;
import com.research.assistant.service.pdf.layout.PaperLayoutArtifact;
import dev.langchain4j.data.message.Content;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

/** Model call for paper understanding. The paper is sent once as one
 * multimodal request; parsing and persistence happen locally after the call. */
@Service
public class PaperMemoryModelService {

    static final String WHOLE_PROMPT_VERSION = "paper-memory-whole-v12-visual-fallback";
    private static final Logger log = LoggerFactory.getLogger(PaperMemoryModelService.class);

    private static final LlmCallPolicy WHOLE_POLICY = new LlmCallPolicy(
            "paper-memory-whole", 360_000, 100_000, 10_000, 1, true, "low");
    private static final Set<String> CLAIM_CATEGORIES = Set.of(
            "CONTRIBUTION", "METHOD", "FINDING", "LIMITATION", "DEFINITION", "OTHER");

    private static final String WHOLE_SYSTEM_PROMPT = """
            你是严谨的论文全局理解器。输入由完整论文的段落级 span、必要的版面区域图像以及可选的原生 PDF 组成。
            只根据输入生成紧凑论文画像，不得引入外部知识，不得把推测写成事实。
            事实必须引用输入中真实出现、且能直接支持陈述的 span ID；不要引用 block ID。
            对性能趋势、比较、数值、原因或鲁棒性等 finding，至少引用一个明确陈述该结论的
            PARAGRAPH 或 ABSTRACT span。AUXILIARY_CAPTION 只帮助理解图表且没有可引用 ID，不得为它编造 ID。
            优先回答“研究什么、如何做、有何贡献、得到什么结论、有何局限”。
            若输入含 LAYOUT_RECOVERY_REGIONS，先根据对应页面或局部图像核对这些区域，再基于核对后的
            内容生成画像。视觉内容区域主要用于理解页面；公式、图表或表格无法逐字可靠确认时，返回
            UNRESOLVED，不得猜测、概括或补写其原始内容。只修复能够准确转写的列出区域。
            不确定的信息留空。只返回 JSON，不要 Markdown、解释或推理过程。
            """;

    private final PaperMemoryModelClient modelClient;
    private final ObjectMapper objectMapper;
    private final PaperWholeDocumentInputBuilder inputBuilder;
    private final PaperMemoryChunker chunker;

    @org.springframework.beans.factory.annotation.Autowired
    public PaperMemoryModelService(PaperMemoryModelClient modelClient,
                                   ObjectMapper objectMapper,
                                   PaperWholeDocumentInputBuilder inputBuilder,
                                   PaperMemoryChunker chunker) {
        this.modelClient = modelClient;
        this.objectMapper = objectMapper;
        this.inputBuilder = inputBuilder;
        this.chunker = chunker;
    }

    /**
     * Understands the complete paper in one model-controlled request. The
     * input builder chooses the already verified native-PDF capability or the
     * structured text plus a bounded set of page or source-region visuals.
     */
    public WholePaperGeneration understandWhole(PaperStructure structure,
                                                PaperLayoutArtifact artifact) {
        if (inputBuilder == null) {
            throw new IllegalStateException("整篇论文输入构建器不可用");
        }
        long totalStarted = System.nanoTime();
        long chunkStarted = System.nanoTime();
        PaperMemoryChunk chunk = (chunker == null
                ? new PaperMemoryChunker(36_000)
                : chunker).wholePaper(structure, artifact);
        long chunkBuildMs = elapsedMs(chunkStarted);
        // The builder appends the ordered source text and page images as later
        // parts of this same user message. Keep the instruction prompt small;
        // putting chunk.text() here as well would send the complete paper twice.
        String prompt = wholePaperRequest(structure);
        long inputStarted = System.nanoTime();
        PaperWholeDocumentInputBuilder.PaperWholeDocumentInput input =
                inputBuilder.build(structure, artifact, prompt);
        long inputBuildMs = elapsedMs(inputStarted);
        if (WHOLE_POLICY.exceedsInputBudget(WHOLE_SYSTEM_PROMPT, input.contents())) {
            throw new IllegalArgumentException("论文正文及视觉内容超过单次理解 Token 预算");
        }
        Generated<WholePaperPayload> generated = generateOnce(
                WHOLE_SYSTEM_PROMPT,
                input.contents(),
                WHOLE_POLICY,
                response -> parseWholePaper(structure, chunk, input, response),
                structure.paperId());
        PaperChunkSummary summary = summaryFromProfile(chunk, generated.value().profile(), generated);
        int recoveryTargetBlockCount = input.recoveryRegions().values().stream()
                .mapToInt(region -> region.blockIds().size()).sum();
        log.info("paper_understanding_timing paperId={} totalMs={} chunkBuildMs={} inputBuildMs={} "
                        + "modelRequestMs={} responseParseMs={} pages={} pageImages={} recoveryImages={} "
                        + "recoveryRegions={} recoveryTargetBlocks={} promptTokens={} completionTokens={}",
                structure.paperId(), elapsedMs(totalStarted), chunkBuildMs, inputBuildMs,
                generated.modelRequestMs(), generated.responseParseMs(), input.pageCount(),
                input.imageCount(), input.recoveryImageCount(), input.recoveryRegions().size(),
                recoveryTargetBlockCount, generated.promptTokens(), generated.completionTokens());
        return new WholePaperGeneration(generated.value().profile(), summary,
                generated.value().recoveries());
    }

    private String wholePaperRequest(PaperStructure structure) {
        String sourceInstruction = "论文原文将作为同一用户消息中的按页结构化文本和页面视觉内容提供；请结合这些内容理解全文。";
        String sourceIds = "可用来源 ID：只能使用结构化论文文本中显示的 span ID";
        String prompt = """
                promptVersion: %s
                paperId: %d
                title: %s
                %s

                请阅读全文后按以下 schema 生成结果。所有章节均已包含，不要逐段复述：
                %s

                提交前逐条检查 keyFindings：凡陈述性能趋势、方案比较、具体数值、原因或鲁棒性，
                evidenceSpanIds 中至少有一个标记为 PARAGRAPH 或 ABSTRACT、且原文明确陈述该结论的 span。
                AUXILIARY_CAPTION 没有可引用 ID；找不到直接正文证据就删除该 finding。

                %s
                """.formatted(
                WHOLE_PROMPT_VERSION,
                structure.paperId(),
                limit(structure.metadata().title(), 500),
                sourceIds,
                wholePaperSchema(),
                sourceInstruction);
        if (WHOLE_POLICY.exceedsInputBudget(WHOLE_SYSTEM_PROMPT, prompt)) {
            throw new IllegalArgumentException("论文正文超过单次理解 Token 预算");
        }
        return WHOLE_POLICY.limitInput(prompt);
    }

    private String wholePaperSchema() {
        return """
                {
                  "domain":"",
                  "researchProblem":"不超过250个汉字",
                  "coreContributions":[{"category":"CONTRIBUTION","statement":"不超过80个汉字",
                    "evidenceSpanIds":["最多3个span-id"],"confidence":0.0}],
                  "methodType":"THEORETICAL|EXPERIMENTAL|SYSTEM|SURVEY|OTHER",
                  "methodSummary":"不超过250个汉字",
                  "datasets":[],"models":[],"metrics":[],
                  "keyFindings":[{"category":"FINDING","statement":"不超过80个汉字",
                    "evidenceSpanIds":["最多3个span-id"],"confidence":0.0}],
                  "limitations":[{"category":"LIMITATION","statement":"不超过80个汉字",
                    "evidenceSpanIds":["最多3个span-id"],"confidence":0.0}],
                  "experimentSetup":{"taskDefinition":"","baselines":""},
                  "benchmarkResults":[{"metric":"","value":"","baseline":"","dataset":"",
                    "evidenceSpanIds":["最多3个span-id"]}],
                  "openQuestions":[],
                  "layoutRecoveries":[
                    {
                      "regionId":"必须来自 LAYOUT_RECOVERY_REGIONS",
                      "status":"CORRECTED|UNRESOLVED",
                      "orderedBlockIds":["只能使用该区域给出的全部 block ID，按视觉顺序排列"],
                      "correctedText":"仅填写能按页面准确转写的原始内容；UNRESOLVED 时留空",
                      "contentType":"TEXT|FORMULA|TABLE|FIGURE"
                    }
                  ]
                }
                layoutRecoveries 对每个给出的 regionId 最多返回一项；没有区域时返回空数组。
                贡献和发现各最多5条，局限最多3条；其余数组各最多5项。
                """;
    }

    private WholePaperPayload parseWholePaper(PaperStructure structure,
                                               PaperMemoryChunk chunk,
                                               PaperWholeDocumentInputBuilder.PaperWholeDocumentInput input,
                                               LlmResponse response) {
        if ("LENGTH".equalsIgnoreCase(response == null ? null : response.getFinishReason())) {
            throw new IllegalArgumentException("全局画像输出被截断");
        }
        JsonNode root = parseObject(response == null ? "" : response.getContent());
        JsonNode profileNode = root.path("profile");
        if (!profileNode.isObject()) profileNode = root;
        LlmResponse profileResponse = new LlmResponse(profileNode.toString(),
                response == null ? 0 : response.getPromptTokens(),
                response == null ? 0 : response.getCompletionTokens(),
                response == null ? 0 : response.getTotalTokens(),
                response == null ? "" : response.getFinishReason());
        PaperGlobalProfile profile = parseProfile(
                structure, sourceSummary(chunk), profileResponse, input.spanBlockIds());
        return new WholePaperPayload(profile,
                parseRecoveries(root.path("layoutRecoveries"), input.recoveryRegions()));
    }

    private List<PaperLayoutRecovery> parseRecoveries(
            JsonNode node, Map<String, LayoutUncertainRegion> regions) {
        if (regions.isEmpty()) return List.of();
        Map<String, JsonNode> returned = new LinkedHashMap<>();
        if (node.isArray()) {
            for (JsonNode item : node) {
                String id = text(item, "regionId", 120);
                if (regions.containsKey(id)) returned.putIfAbsent(id, item);
            }
        }
        List<PaperLayoutRecovery> result = new ArrayList<>();
        for (LayoutUncertainRegion region : regions.values()) {
            JsonNode item = returned.get(region.regionId());
            if (item == null || !"CORRECTED".equalsIgnoreCase(text(item, "status", 24))) {
                result.add(unresolved(region));
                continue;
            }
            // Visual regions remain available through their page locator and
            // neighbouring parsed text. A partial visual transcription is less
            // useful than that honest fallback, especially for mathematics.
            if ("VISUAL_CONTENT".equals(region.issueType())) {
                result.add(unresolved(region));
                continue;
            }
            List<String> ordered = allowedStrings(item.path("orderedBlockIds"),
                    new LinkedHashSet<>(region.blockIds()), region.blockIds().size());
            if (ordered.isEmpty()) ordered = region.blockIds();
            int maxCorrectionLength = Math.max(2_000,
                    Math.min(12_000, region.rawText().length() * 3 + 500));
            String corrected = item.path("correctedText").asText("").strip();
            if (!new LinkedHashSet<>(ordered).equals(new LinkedHashSet<>(region.blockIds()))
                    || corrected.isBlank() || corrected.length() > maxCorrectionLength
                    || ("READING_ORDER".equals(region.issueType())
                    && tokenCoverage(region.rawText(), corrected) < 0.60)) {
                result.add(unresolved(region));
                continue;
            }
            String contentType = text(item, "contentType", 24).toUpperCase(Locale.ROOT);
            if (!Set.of("TEXT", "FORMULA", "TABLE", "FIGURE").contains(contentType)) contentType = "TEXT";
            result.add(new PaperLayoutRecovery(region.regionId(), "CORRECTED", region.issueType(),
                    ordered, region.pageAreas(), corrected, contentType, "VISUAL_RECOVERY"));
        }
        return List.copyOf(result);
    }

    private PaperLayoutRecovery unresolved(LayoutUncertainRegion region) {
        return new PaperLayoutRecovery(region.regionId(), "UNRESOLVED", region.issueType(),
                region.blockIds(), region.pageAreas(), "", "TEXT", "VISUAL_RECOVERY");
    }

    private double tokenCoverage(String source, String corrected) {
        Set<String> sourceTokens = new LinkedHashSet<>(List.of(SourceText.tokens(source)));
        Set<String> correctedTokens = new LinkedHashSet<>(List.of(SourceText.tokens(corrected)));
        sourceTokens.removeIf(String::isBlank);
        if (sourceTokens.isEmpty()) return 1;
        long matched = sourceTokens.stream().filter(correctedTokens::contains).count();
        return matched / (double) sourceTokens.size();
    }

    private PaperGlobalProfile parseProfile(PaperStructure structure,
                                            PaperChunkSummary source,
                                            LlmResponse response,
                                            Map<String, List<String>> spanBlockIds) {
        if ("LENGTH".equalsIgnoreCase(response == null ? null : response.getFinishReason())) {
            throw new IllegalArgumentException("全局画像输出被截断");
        }
        JsonNode root = parseObject(response == null ? "" : response.getContent());
        Set<String> allowedBlocks = new LinkedHashSet<>(source.blockIds());
        LinkedHashSet<String> issues = new LinkedHashSet<>();
        List<PaperMemoryClaim> contributions = claims(
                root.path("coreContributions"), allowedBlocks, spanBlockIds, issues);
        List<PaperMemoryClaim> findings = claims(
                root.path("keyFindings"), allowedBlocks, spanBlockIds, issues);
        List<PaperMemoryClaim> limitations = claims(
                root.path("limitations"), allowedBlocks, spanBlockIds, issues);
        String researchProblem = text(root, "researchProblem", 800);
        String methodSummary = text(root, "methodSummary", 800);
        if (researchProblem.isBlank() && contributions.isEmpty() && methodSummary.isBlank()) {
            throw new IllegalArgumentException("全局画像缺少可用的核心内容");
        }
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
                benchmarks(root.path("benchmarkResults"), allowedBlocks, spanBlockIds, issues),
                List.of(),
                strings(root.path("openQuestions"), 5, 300),
                new PaperGlobalProfile.Coverage(1, 1, 0, true),
                List.copyOf(issues), Instant.now());
    }

    private List<PaperMemoryClaim> claims(JsonNode node,
                                          Set<String> allowedBlocks,
                                          Map<String, List<String>> spanBlockIds,
                                          Set<String> issues) {
        if (!node.isArray()) return List.of();
        List<PaperMemoryClaim> result = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isObject() || result.size() >= 5) break;
            String statement = text(item, "statement", 320);
            if (statement.isBlank()) continue;
            List<String> evidence = evidenceBlocks(item, allowedBlocks, spanBlockIds);
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
            JsonNode node, Set<String> allowedBlocks,
            Map<String, List<String>> spanBlockIds, Set<String> issues) {
        if (!node.isArray()) return List.of();
        List<PaperGlobalProfile.BenchmarkResult> result = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isObject() || result.size() >= 5) break;
            List<String> evidence = evidenceBlocks(item, allowedBlocks, spanBlockIds);
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

    private List<String> evidenceBlocks(JsonNode item,
                                        Set<String> allowedBlocks,
                                        Map<String, List<String>> spanBlockIds) {
        if (!spanBlockIds.isEmpty()) {
            List<String> spanIds = allowedStrings(
                    item.path("evidenceSpanIds"), spanBlockIds.keySet(), 3);
            if (!spanIds.isEmpty()) {
                return spanIds.stream()
                        .flatMap(id -> spanBlockIds.getOrDefault(id, List.of()).stream())
                        .filter(allowedBlocks::contains)
                        .distinct()
                        .toList();
            }
        }
        return allowedStrings(item.path("evidenceBlockIds"), allowedBlocks, 3);
    }

    /** One-shot parser for the active whole-paper path. No repair request is
     * issued: a malformed response is recorded as a real model failure so the
     * measured call count remains truthful. */
    private <T> Generated<T> generateOnce(String systemPrompt,
                                           List<Content> userContents,
                                           LlmCallPolicy policy,
                                           Function<LlmResponse, T> parser,
                                           long paperId) {
        LlmResponse primary;
        long modelStarted = System.nanoTime();
        try {
            primary = modelClient.chat(systemPrompt, userContents, policy);
        } catch (RuntimeException exception) {
            throw new PaperMemoryGenerationException(
                "论文整篇理解模型调用失败", exception, 0, 0, "");
        }
        long modelRequestMs = elapsedMs(modelStarted);
        int promptTokens = count(primary == null ? null : primary.getPromptTokens());
        int completionTokens = count(primary == null ? null : primary.getCompletionTokens());
        long parseStarted = System.nanoTime();
        try {
            return new Generated<>(parser.apply(primary), promptTokens, completionTokens,
                    primary == null ? "" : primary.getFinishReason(), modelRequestMs,
                    elapsedMs(parseStarted));
        } catch (RuntimeException parseFailure) {
            log.warn("paper_understanding_response_parse_failed paperId={} modelRequestMs={} promptTokens={} completionTokens={}",
                    paperId, modelRequestMs, promptTokens, completionTokens);
            throw new PaperMemoryGenerationException(
                    "论文整篇理解输出无法解析", parseFailure,
                    promptTokens, completionTokens,
                    primary == null ? "" : primary.getFinishReason());
        }
    }

    private PaperChunkSummary sourceSummary(PaperMemoryChunk chunk) {
        return new PaperChunkSummary(
                chunk.id(), chunk.sourceFingerprint(), chunk.ordinal(), chunk.sectionId(),
                chunk.headingPath(), chunk.pageStart(), chunk.pageEnd(), chunk.blockIds(),
                "整篇论文来源", List.of(), List.of(), List.of(), List.of(), List.of(),
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

    private long elapsedMs(long started) {
        return Math.max(0, (System.nanoTime() - started) / 1_000_000);
    }

    private record WholePaperPayload(PaperGlobalProfile profile,
                                     List<PaperLayoutRecovery> recoveries) { }

    private static final class SourceText {
        private static String[] tokens(String value) {
            return (value == null ? "" : value.toLowerCase(Locale.ROOT))
                    .replaceAll("[^\\p{L}\\p{N}]+", " ").trim().split("\\s+");
        }
    }

    private record Generated<T>(T value,
                                int promptTokens,
                                int completionTokens,
                                String finishReason,
                                long modelRequestMs,
                                long responseParseMs) {
        private Generated {
            promptTokens = Math.max(0, promptTokens);
            completionTokens = Math.max(0, completionTokens);
            finishReason = finishReason == null ? "" : finishReason;
            modelRequestMs = Math.max(0, modelRequestMs);
            responseParseMs = Math.max(0, responseParseMs);
        }
    }

    public record WholePaperGeneration(PaperGlobalProfile profile,
                                       PaperChunkSummary summary,
                                       List<PaperLayoutRecovery> recoveries) {
        public WholePaperGeneration {
            recoveries = recoveries == null ? List.of() : List.copyOf(recoveries);
        }

        public WholePaperGeneration(PaperGlobalProfile profile, PaperChunkSummary summary) {
            this(profile, summary, List.of());
        }
    }
}
