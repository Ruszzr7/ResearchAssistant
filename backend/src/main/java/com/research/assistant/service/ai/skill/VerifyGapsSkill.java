package com.research.assistant.service.ai.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.common.JsonUtils;
import com.research.assistant.service.ArxivFetcher;
import com.research.assistant.service.LLMService;
import com.research.assistant.service.SemanticScholarFetcher;
import com.research.assistant.service.ai.ResearchToolAgent;
import com.research.assistant.service.analysis.GapEvidenceScorer;
import com.research.assistant.service.rag.RagRetrievalService;
import com.research.assistant.service.rag.ScoredChunk;
import com.research.assistant.service.rag.EvidenceValidator;
import com.research.assistant.service.source.CitationNetworkExpansionService;
import com.research.assistant.service.source.LiteratureCandidate;
import com.research.assistant.service.source.LiteratureSearchService;
import com.research.assistant.service.reliability.ExternalCallPolicy;
import com.research.assistant.service.reliability.ExternalCallResult;
import dev.langchain4j.service.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Lazy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Gap 外部验证 Skill。
 */
@Component
public class VerifyGapsSkill implements Skill<String, List<Map<String, Object>>> {

    private static final Logger log = LoggerFactory.getLogger(VerifyGapsSkill.class);

    private final ResearchToolAgent researchToolAgent;
    private final ArxivFetcher arxivFetcher;
    private final SemanticScholarFetcher semanticScholarFetcher;
    private final LLMService llmService;
    private final ObjectMapper objectMapper;
    private final RagRetrievalService ragRetrievalService;
    private final CitationNetworkExpansionService citationNetworkExpansionService;
    private final LiteratureSearchService literatureSearchService;
    private final EvidenceValidator evidenceValidator;
    private final ExternalCallPolicy externalCallPolicy;

    public VerifyGapsSkill(@Lazy ResearchToolAgent researchToolAgent,
                           ArxivFetcher arxivFetcher,
                           SemanticScholarFetcher semanticScholarFetcher,
                           LLMService llmService,
                           ObjectMapper objectMapper,
                           RagRetrievalService ragRetrievalService,
                           CitationNetworkExpansionService citationNetworkExpansionService,
                           LiteratureSearchService literatureSearchService) {
        this(researchToolAgent, arxivFetcher, semanticScholarFetcher, llmService, objectMapper,
                ragRetrievalService, citationNetworkExpansionService, literatureSearchService,
                new EvidenceValidator(), new ExternalCallPolicy());
    }

    @Autowired
    public VerifyGapsSkill(@Lazy ResearchToolAgent researchToolAgent,
                           ArxivFetcher arxivFetcher,
                           SemanticScholarFetcher semanticScholarFetcher,
                           LLMService llmService,
                           ObjectMapper objectMapper,
                           RagRetrievalService ragRetrievalService,
                           CitationNetworkExpansionService citationNetworkExpansionService,
                           LiteratureSearchService literatureSearchService,
                           EvidenceValidator evidenceValidator,
                           ExternalCallPolicy externalCallPolicy) {
        this.researchToolAgent = researchToolAgent;
        this.arxivFetcher = arxivFetcher;
        this.semanticScholarFetcher = semanticScholarFetcher;
        this.llmService = llmService;
        this.objectMapper = objectMapper;
        this.ragRetrievalService = ragRetrievalService;
        this.citationNetworkExpansionService = citationNetworkExpansionService;
        this.literatureSearchService = literatureSearchService;
        this.evidenceValidator = evidenceValidator;
        this.externalCallPolicy = externalCallPolicy;
    }

    public VerifyGapsSkill(@Lazy ResearchToolAgent researchToolAgent,
                           ArxivFetcher arxivFetcher,
                           SemanticScholarFetcher semanticScholarFetcher,
                           LLMService llmService,
                           ObjectMapper objectMapper,
                           RagRetrievalService ragRetrievalService,
                           CitationNetworkExpansionService citationNetworkExpansionService,
                           LiteratureSearchService literatureSearchService,
                           EvidenceValidator evidenceValidator) {
        this(researchToolAgent, arxivFetcher, semanticScholarFetcher, llmService, objectMapper,
                ragRetrievalService, citationNetworkExpansionService, literatureSearchService,
                evidenceValidator, new ExternalCallPolicy());
    }

    @Override
    public String name() {
        return Skills.VERIFY_GAPS;
    }

    @Override
    public String description() {
        return "对 Gap 报告进行外部多源验证（arXiv + Semantic Scholar + LLM 语义评估）。" +
                "输入：{\"gapReport\": \"markdown 字符串\"}；输出：List<Map<String,Object>>（每个 Gap 的验证结果，含 evidence 列表）。";
    }

    @Override
    public Class<String> inputType() {
        return String.class;
    }

    @Override
    public List<Map<String, Object>> execute(SkillContext ctx, String gapReport) {
        if (gapReport == null || gapReport.isBlank()) {
            return List.of();
        }

        ctx.stage("正在进行外部验证…");
        try {
            Result<String> result = researchToolAgent.verifyGaps(gapReport);
            String json = result != null ? result.content() : "";
            if (json != null && !json.isBlank()) {
                json = JsonUtils.extractJson(json);
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> verified = objectMapper.readValue(json, List.class);
                for (Map<String, Object> item : verified) {
                    item.put("searchDepth", "LLM 工具调用：arXiv + Semantic Scholar + PDF 提取综合验证");
                    Object rawEvidence = item.get("evidence");
                    if (rawEvidence instanceof List<?> list) {
                        List<Map<String, Object>> maps = list.stream()
                                .filter(Map.class::isInstance)
                                .map(value -> (Map<String, Object>) value)
                                .toList();
                        item.put("evidence", evidenceValidator.markAgentEvidenceUnverified(maps));
                        item.put("verifiedEvidenceCount", 0);
                        item.put("unverifiedEvidenceCount", maps.size());
                    }
                }
                return verified;
            }
        } catch (Exception e) {
            log.warn("Agent Gap 验证失败，将回退到多源语义验证", e);
        }

        return verifyGapsWithEvidence(gapReport);
    }

    /**
     * 多源语义验证 fallback：
     * 1. 拆分 Gap 报告；
     * 2. 为每个 Gap 生成多组搜索查询；
     * 3. 顺序搜索 arXiv + Semantic Scholar；
     * 4. 用 LLM 判断候选论文是否真正覆盖该 Gap，并抽取证据片段；
     * 5. 按证据质量输出 red/yellow/green。
     */
    private List<Map<String, Object>> verifyGapsWithEvidence(String gapReport) {
        List<Map<String, Object>> verified = new ArrayList<>();
        List<GapPart> parts = splitGapReport(gapReport);

        for (GapPart part : parts) {
            verified.add(verifySingleGap(part));
        }
        return verified.isEmpty() ? fallbackSingleReport(gapReport) : verified;
    }

    private Map<String, Object> verifySingleGap(GapPart part) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("gapTitle", part.title);

        List<String> queries = generateSearchQueries(part);
        List<Map<String, Object>> candidates = collectCandidates(queries);
        collectLocalCandidates(part, candidates);
        List<Map<String, Object>> networkCoverage = expandByCitationNetwork(candidates);

        if (candidates.isEmpty()) {
            item.put("level", "red");
            item.put("reason", "在 arXiv 与 Semantic Scholar 均未检索到相关候选论文");
            item.put("evidence", List.of());
            item.put("searchDepth", "摘要级多源搜索（arXiv + Semantic Scholar），未检索付费墙后正文");
            item.put("searchQueries", queries);
            item.put("resultCount", 0);
            item.put("networkCoverage", networkCoverage);
            return item;
        }

        List<Map<String, Object>> evidence = evaluateEvidence(part, candidates);
        double weightedScore = GapEvidenceScorer.score(evidence);
        String level = GapEvidenceScorer.determineLevel(weightedScore);
        String reason = buildReason(evidence, level, weightedScore);

        item.put("level", level);
        item.put("reason", reason);
        item.put("evidence", evidence);
        item.put("networkCoverage", networkCoverage);
        item.put("searchDepth", "摘要级多源搜索 + 引用网络扩展 + LLM 语义比对");
        item.put("searchQueries", queries);
        item.put("resultCount", evidence.size());
        item.put("candidateCount", candidates.size());
        item.put("verifiedEvidenceCount", evidence.size());
        return item;
    }

    private List<GapPart> splitGapReport(String gapReport) {
        List<GapPart> parts = new ArrayList<>();
        String[] sections = Pattern.compile("(?=^###\\s+|^Gap\\s*\\d)", Pattern.MULTILINE)
                .split(gapReport);
        for (String sec : sections) {
            String trimmed = sec.trim();
            if (trimmed.isEmpty()) continue;
            String first = trimmed.split("\\n", 2)[0].trim();
            String title = first.replaceAll("^#+\\s*", "").replaceAll("[🔴🟡🟢]", "").trim();
            if (title.isEmpty()) continue;
            parts.add(new GapPart(title, trimmed));
        }
        return parts;
    }

    private List<String> generateSearchQueries(GapPart part) {
        // 启发式查询：取标题前 6 个实词，再补一个带 "survey" 的查询
        String[] words = part.title.split("\\s+");
        String base = String.join(" ", java.util.Arrays.copyOf(words, Math.min(6, words.length)));
        List<String> queries = new ArrayList<>();
        queries.add(base);
        if (part.title.toLowerCase(Locale.ROOT).contains("survey") || part.title.toLowerCase(Locale.ROOT).contains("review")) {
            queries.add(base + " method");
        } else {
            queries.add(base + " survey");
        }
        return queries;
    }

    private List<Map<String, Object>> collectCandidates(List<String> queries) {
        Set<String> seen = new LinkedHashSet<>();
        List<Map<String, Object>> all = new ArrayList<>();

        for (String query : queries) {
            searchSource(query, arxivFetcher::search, all, seen, "arXiv");
            searchSource(query, semanticScholarFetcher::search, all, seen, "Semantic Scholar");
        }
        return all.stream().limit(12).collect(Collectors.toList());
    }

    /**
     * 从本地向量知识库检索与 Gap 语义相似的论文片段，作为补充证据。
     */
    private void collectLocalCandidates(GapPart part, List<Map<String, Object>> candidates) {
        try {
            List<ScoredChunk> chunks = ragRetrievalService.retrieveAndRerank(
                    part.title + "\n" + part.body, 10, 0.65);
            for (int i = 0; i < chunks.size(); i++) {
                ScoredChunk c = chunks.get(i);
                Map<String, Object> normalized = new LinkedHashMap<>();
                normalized.put("title", "本地论文库片段 #" + (i + 1));
                normalized.put("summary", c.content());
                normalized.put("published", "");
                normalized.put("sourceUrl", "");
                normalized.put("source", "Local Library");
                normalized.put("pdfUrl", "");
                normalized.put("externalId", "");
                normalized.put("paperId", c.paperId());
                normalized.put("chunkKey", c.chunkKey());
                normalized.put("chunkId", c.chunkKey());
                normalized.put("chunkType", c.chunkType());
                normalized.put("sourceType", c.sourceType());
                normalized.put("score", c.score());
                normalized.put("indexVersion", c.indexVersion());
                normalized.put("evidenceId", c.evidenceId());
                normalized.put("locator", locator(c));
                candidates.add(normalized);
            }
        } catch (Exception e) {
            log.debug("本地向量库检索失败: {}", e.getMessage());
        }
    }

    /**
     * 对带有 S2 ID 的候选论文做前向/后向引用扩展，返回网络覆盖证据列表。
     */
    private List<Map<String, Object>> expandByCitationNetwork(List<Map<String, Object>> candidates) {
        List<Map<String, Object>> coverage = new ArrayList<>();
        List<Map<String, Object>> toAdd = new ArrayList<>();
        Set<String> expandedIds = new LinkedHashSet<>();
        for (Map<String, Object> c : candidates) {
            String source = String.valueOf(c.getOrDefault("source", ""));
            String externalId = String.valueOf(c.getOrDefault("externalId", ""));
            if (!externalId.isBlank()
                    && (source.contains("Semantic Scholar") || source.contains("OpenAlex") || source.contains("IEEE") || source.contains("ACM"))
                    && expandedIds.add(externalId)) {
                try {
                    List<LiteratureCandidate> related = citationNetworkExpansionService.expandByS2Id(
                            externalId, List.of("forward", "backward"), 5);
                    for (LiteratureCandidate r : related) {
                        if (r == null || r.title() == null || r.title().isBlank()) continue;
                        Map<String, Object> map = new LinkedHashMap<>(r.toMap());
                        double weight = GapEvidenceScorer.weightByYear(r.year());
                        map.put("networkWeight", weight);
                        coverage.add(map);
                        boolean exists = candidates.stream().anyMatch(existing ->
                                r.title().equalsIgnoreCase(String.valueOf(existing.getOrDefault("title", ""))));
                        if (!exists) {
                            toAdd.add(map);
                        }
                    }
                } catch (Exception e) {
                    log.debug("引用网络扩展失败 externalId={}: {}", externalId, e.getMessage());
                }
            }
        }
        candidates.addAll(toAdd);
        return coverage;
    }

    @FunctionalInterface
    private interface SourceSearch {
        List<Map<String, Object>> search(String query, int limit) throws Exception;
    }

    private void searchSource(String query, SourceSearch source,
                              List<Map<String, Object>> out, Set<String> seen, String sourceName) {
        try {
            ExternalCallResult<List<Map<String, Object>>> call = externalCallPolicy.executeWithStatus(
                    "literature_" + sourceName,
                    () -> source.search(query, 5),
                    () -> List.<Map<String, Object>>of());
            List<Map<String, Object>> results = call.value();
            for (Map<String, Object> r : results) {
                String title = String.valueOf(r.getOrDefault("title", "")).trim().toLowerCase(Locale.ROOT);
                if (title.isBlank() || !seen.add(title)) continue;
                Map<String, Object> normalized = new LinkedHashMap<>();
                normalized.put("title", r.getOrDefault("title", ""));
                normalized.put("summary", r.getOrDefault("summary", ""));
                normalized.put("published", r.getOrDefault("published", ""));
                normalized.put("sourceUrl", r.getOrDefault("sourceUrl", ""));
                normalized.put("source", r.getOrDefault("source", sourceName));
                normalized.put("pdfUrl", r.getOrDefault("pdfUrl", ""));
                normalized.put("externalId", r.getOrDefault("paperId", ""));
                normalized.put("evidenceId", externalEvidenceId(
                        String.valueOf(r.getOrDefault("source", sourceName)),
                        String.valueOf(r.getOrDefault("paperId", "")),
                        String.valueOf(r.getOrDefault("title", ""))));
                out.add(normalized);
            }
        } catch (Exception e) {
            log.debug("搜索失败 query={}, source={}: {}", query, sourceName, e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> evaluateEvidence(GapPart part, List<Map<String, Object>> candidates) {
        StringBuilder sb = new StringBuilder();
        sb.append("研究空白标题：").append(part.title).append("\n");
        sb.append("研究空白详细描述：\n").append(part.body).append("\n\n");
        sb.append("候选论文列表（仅标题与摘要）：\n");
        for (int i = 0; i < candidates.size(); i++) {
            Map<String, Object> c = candidates.get(i);
            sb.append(i + 1).append(". ").append(c.get("title")).append("\n");
            sb.append("   来源：").append(c.get("source")).append(" | 年份：").append(c.get("published")).append("\n");
            sb.append("   摘要：").append(truncate(String.valueOf(c.getOrDefault("summary", "")), 400)).append("\n");
        }

        String system = """
                你是一位严谨的学术验证助手。请根据给定的研究空白（Gap）和候选论文列表，
                判断哪些论文真正针对或覆盖了该 Gap。只返回一个 JSON 数组，不要 markdown 代码块，不要其他解释。

                数组中每个元素格式：
                {
                  "evidenceId": "必须来自候选论文列表，不要自行生成",
                  "title": "论文标题（与候选列表一致）",
                  "source": "arXiv 或 Semantic Scholar",
                  "year": "2024",
                  "snippet": "该论文与 Gap 直接相关的摘要或方法片段，50-100 字，必须来自候选论文摘要",
                  "url": "忽略，后端会从候选记录回填"
                }

                只有确实直接相关、能作为 Gap 已被覆盖或部分覆盖的证据时才加入数组。不要牵强附会。
                """;
        try {
            String response = llmService.chat(system, sb.toString());
            String json = JsonUtils.extractJson(response);
            if (json == null || json.isBlank()) return List.of();
            List<Map<String, Object>> evidence = objectMapper.readValue(json, List.class);
            return evidenceValidator.validate(evidence, candidates);
        } catch (Exception e) {
            log.warn("LLM 证据评估失败: {}", e.getMessage());
            return List.of();
        }
    }

    private String buildReason(List<Map<String, Object>> evidence, String level, double weightedScore) {
        return switch (level) {
            case "green" ->
                    String.format(Locale.US, "发现 %.1f 分直接相关证据（加权后），Gap 很可能已被覆盖", weightedScore);
            case "yellow" ->
                    String.format(Locale.US, "发现 %.1f 分部分相关证据，需进一步验证", weightedScore);
            default -> String.format(Locale.US, "仅发现 %.1f 分证据，未发现明确覆盖该 Gap 的候选论文", weightedScore);
        };
    }

    private String truncate(String text, int max) {
        if (text == null || text.length() <= max) return text;
        return text.substring(0, max) + "…";
    }

    private String externalEvidenceId(String source, String externalId, String title) {
        String identity = externalId == null || externalId.isBlank() ? title : externalId;
        return "external:" + source.toLowerCase(Locale.ROOT).replaceAll("\\s+", "-") + ":" + identity;
    }

    private Map<String, Object> locator(ScoredChunk chunk) {
        Map<String, Object> locator = new LinkedHashMap<>();
        if (chunk.pageStart() != null) locator.put("pageStart", chunk.pageStart());
        if (chunk.pageEnd() != null) locator.put("pageEnd", chunk.pageEnd());
        if (chunk.charStart() != null) locator.put("charStart", chunk.charStart());
        if (chunk.charEnd() != null) locator.put("charEnd", chunk.charEnd());
        if (chunk.source() != null) locator.put("source", chunk.source());
        return locator;
    }

    private List<Map<String, Object>> fallbackSingleReport(String gapReport) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("gapTitle", "完整报告");
        item.put("level", "yellow");
        item.put("reason", "无法拆分 Gap，外部验证未完成");
        item.put("evidence", List.of());
        item.put("searchDepth", "回退：未执行外部检索");
        return List.of(item);
    }

    private record GapPart(String title, String body) {
    }
}
