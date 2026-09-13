package com.research.assistant.service.agent.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.entity.PaperMemoryRecord;
import com.research.assistant.mapper.PaperMemoryMapper;
import com.research.assistant.service.agent.source.PaperSearchRequest;
import com.research.assistant.service.agent.source.PaperSourceCatalog;
import com.research.assistant.service.agent.source.PaperSourceCatalogService;
import com.research.assistant.service.agent.source.RetrievalHit;
import com.research.assistant.service.agent.source.SourceContentType;
import com.research.assistant.service.agent.source.SourceEvidenceQuality;
import com.research.assistant.service.agent.source.SourceObject;
import com.research.assistant.service.memory.PaperGlobalProfile;
import com.research.assistant.service.memory.PaperMemoryClaim;
import com.research.assistant.service.memory.PaperUnderstandingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class PaperReadToolRegistry {

    // This is only a payload-size boundary for one batch request, not an Agent
    // call/round limit. The Agent may submit another request when needed.
    static final int MAX_SEARCHES_PER_REQUEST = 4;
    static final int MAX_QUERY_CHARACTERS = 400;
    // Keep a larger deterministic candidate page internally; only a small,
    // payload-bounded subset is returned to the model. Remaining candidates
    // are exposed through nextCursor instead of being silently dropped.
    static final int MAX_SEARCH_RESULTS = 50;
    static final int MAX_PAGE_SPAN = 2;
    // Keep each read compact enough for the next model turn. The Agent may request
    // another read when it needs a different or missing part of the paper.
    static final int MAX_SOURCES_PER_RESULT = 6;
    static final int MAX_TARGETED_SOURCES_PER_RESULT = 8;
    static final int MAX_CONTENT_CHARACTERS = 8_000;
    static final int MAX_RESULT_BYTES = 16 * 1024;
    private static final Pattern SUBFORMULA_NUMBER = Pattern.compile("^(\\d{1,4})([a-z])$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern FORMULA_TARGET = Pattern.compile(
            "(?i)^(?:(?:公式(?:编号)?|方程(?:式)?(?:编号)?)\\s*|(?:eq(?:uation)?|formula)\\.?\\s*)?"
                    + "\\(?([0-9]{1,4}[a-z]?)\\)?$");
    private static final Pattern FIGURE_REFERENCE = Pattern.compile(
            "(?i)\\bfig(?:ure)?s?\\.?\\s*([0-9IVX]+[a-z]?)\\b");
    private static final String EVIDENCE_SCHEMA = """
            {"type":"object","properties":{
            "needs":{"type":"array","minItems":1,"maxItems":4,"items":{"type":"object","properties":{
            "id":{"type":"string","minLength":1,"maxLength":40,"description":"本条证据需求的稳定唯一 ID；补检索同一事实时保持不变。"},
            "objective":{"type":"string","minLength":1,"maxLength":240,"description":"用中文、中立地说明要确认的一个独立事实；补检索时原样保留。"},
            "query":{"type":"string","minLength":1,"maxLength":400,"description":"使用论文原文术语、变量名、指标名、数值或公式编号的检索表达。"},
            "keywords":{"type":"array","minItems":1,"maxItems":8,"uniqueItems":true,"items":{"type":"string","minLength":1,"maxLength":120,"description":"首次检索无结果时使用的少量论文原文核心词。"}},
            "targets":{"type":"array","minItems":1,"maxItems":8,"uniqueItems":true,"items":{"type":"string","minLength":1,"maxLength":120,"description":"可在论文原文中做词面核对的术语、变量、数值、公式或基线；不要填写中文语义结论。"}},
            "sectionHint":{"type":"string","minLength":1,"maxLength":120,"description":"有明确依据时填写论文原文章节名称。"},
            "pageHints":{"type":"array","minItems":1,"maxItems":2,"uniqueItems":true,"items":{"type":"integer","minimum":1},"description":"有明确依据时填写可能所在的页码或连续页码范围。"},
            "cursor":{"type":"integer","minimum":0,"maximum":10000,"description":"仅使用上一次返回的 nextCursor 继续读取该 Need 的候选页。"},
            "profileClaimRefs":{"type":"array","minItems":1,"maxItems":4,"uniqueItems":true,"items":{"type":"string","minLength":1,"maxLength":40},"description":"画像返回的可信 claimRef；直接复用其已绑定来源。"},
            "contentTypes":{"type":"array","minItems":1,"maxItems":5,"uniqueItems":true,"items":{"type":"string","enum":["TEXT","FORMULA","TABLE","FIGURE","ALGORITHM"]},"description":"有明确依据时限制来源内容类型。用户直接询问某个图或图中趋势时必须包含 FIGURE；返回的 FIGURE 来源会自动附加实际图像区域。"},
            "sourceObjectIds":{"type":"array","minItems":1,"maxItems":4,"uniqueItems":true,"items":{"type":"string","minLength":1,"maxLength":160},"description":"已知可信来源 ID；直接读取，不要重新搜索。"},
            "includeVisual":{"type":"boolean","description":"需要核对图表内容、页面二维布局或文本不可靠的公式时，附加与来源绑定的局部图像。"},
            "refinementReason":{"type":"string","minLength":1,"maxLength":240,"description":"补检索时说明上一批来源还缺少什么，以及为何新条件可能得到不同证据；首次调用省略。"}},
            "required":["id","objective"],"additionalProperties":false}},
            "pageRanges":{"type":"array","minItems":1,"maxItems":2,"description":"需要直接读取的连续页码范围，每个范围最多两页。","items":{"type":"object","properties":{"startPage":{"type":"integer","minimum":1},"endPage":{"type":"integer","minimum":1}},"required":["startPage","endPage"],"additionalProperties":false}},
            "maxEvidence":{"type":"integer","minimum":1,"maximum":8,"description":"希望返回的最大来源数；服务端仍会按来源类型和负载上限裁剪。"}},"additionalProperties":false}
            """;

    private final PaperSourceCatalogService sourceService;
    private final ObjectMapper objectMapper;
    private final PaperMemoryMapper memoryMapper;

    public PaperReadToolRegistry(PaperSourceCatalogService sourceService, ObjectMapper objectMapper) {
        this(sourceService, objectMapper, null);
    }

    @Autowired
    public PaperReadToolRegistry(PaperSourceCatalogService sourceService, ObjectMapper objectMapper,
                                 PaperMemoryMapper memoryMapper) {
        this.sourceService = sourceService;
        this.objectMapper = objectMapper;
        this.memoryMapper = memoryMapper;
    }

    public List<AgentToolDefinition> definitions() {
        return List.of(new AgentToolDefinition("retrieve_paper_evidence",
                "从当前论文检索可引用的原文证据。先把最终答案需要成立的独立事实拆成 1～4 个 needs，并在首次调用中一次提交；首次有效检索后 Need ID 集合冻结。每个 Need 都要提供稳定唯一的 id、中文中立的 objective，以及 query、sourceObjectIds 或 profileClaimRefs 中至少一种检索锚点。query、keywords 和 targets 使用论文原文术语、变量名、数值或公式编号；targets 只做词面核对。用户直接询问图或图中趋势时，该 Need 必须使用 contentTypes=[\"FIGURE\"]，并在 targets/query 中保留明确的 Fig./Figure 编号；实际图像区域会随匹配的 FIGURE 来源一起返回。解释图的正文可作为另一个 TEXT Need。回答精确公式时优先限制 FORMULA，公式文本不可靠时工具会自动返回局部图像。收到来源后由 Agent 阅读并判断语义充分性；只对未解决 Need 保持原 id 和 objective，补检索时填写 refinementReason 并改变有效检索条件。若返回 coverageState=LEXICAL_TARGETS_COVERED，或明确返回了目标图/图题，停止该 Need 并使用 submit_answer；不要为了穷尽候选而继续检索。若返回 hasMore=true，只有仍缺少目标或正文时才使用同一 Need 的 nextCursor 继续读取候选页；若没有新来源、游标已耗尽或 stopRecommended=true，停止该 Need并使用 submit_answer。discussesFigure/discussedBy 中的来源 ID 只是关系定位提示，不是本次已读取或可引用的来源；引用前必须把该 ID 作为 sourceObjectIds 重新交给本工具读取。不要重复请求、新建同方向 Need，或把检索状态当成论文结论。", EVIDENCE_SCHEMA));
    }

    /** Compatibility entry point; model-facing tools no longer vary by message keywords. */
    public List<AgentToolDefinition> definitions(String userMessage) {
        return definitions();
    }

    public AgentToolExecution execute(PaperSourceCatalog catalog, String name, String argumentsJson) {
        String evidenceFocus = null;
        try {
            evidenceFocus = objectMapper.readTree(argumentsJson).path("_evidenceFocus").asText(null);
        } catch (Exception ignored) {
            // The normal parser below owns malformed-input reporting.
        }
        return execute(catalog, name, argumentsJson, evidenceFocus);
    }

    public AgentToolExecution execute(PaperSourceCatalog catalog, String name, String argumentsJson,
                                      String evidenceFocus) {
        try {
            JsonNode args = objectMapper.readTree(argumentsJson);
            return switch (name) {
                case "retrieve_paper_evidence" -> retrieveEvidence(catalog, args, evidenceFocus);
                case "read_pages" -> readPages(catalog, args);
                default -> throw new IllegalArgumentException("未知的只读工具：" + name);
            };
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            if ("retrieve_paper_evidence".equals(name)) {
                return invalidRequest(List.of(issue(null, "request", "MALFORMED_JSON",
                        "证据检索参数必须是有效的 JSON 对象")));
            }
            throw new IllegalArgumentException("工具参数无效：" + name, error);
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException("工具参数无效：" + name, error);
        }
    }

    private AgentToolExecution retrieveEvidence(PaperSourceCatalog catalog, JsonNode args,
                                                 String evidenceFocus) throws Exception {
        List<Map<String, Object>> validationIssues = validateEvidenceRequest(args);
        if (!validationIssues.isEmpty()) return invalidRequest(validationIssues);
        JsonNode requestedNeeds = args.path("needs");
        JsonNode pageRanges = args.path("pageRanges");
        boolean hasSearches = requestedNeeds.isArray() && !requestedNeeds.isEmpty();
        boolean hasPages = pageRanges.isArray() && !pageRanges.isEmpty();
        Map<String, MergedHit> merged = new LinkedHashMap<>();
        Map<Integer, List<MergedHit>> hitsBySearch = new LinkedHashMap<>();
        Map<Integer, List<String>> invalidSourceIdsBySearch = new LinkedHashMap<>();
        Map<Integer, List<String>> invalidClaimRefsBySearch = new LinkedHashMap<>();
        Map<SearchRequestKey, List<RetrievalHit>> searchCache = new LinkedHashMap<>();
        ProfileClaimIndex claimIndex = profileClaimIndex(catalog);
        Set<String> matchedProfileClaimRefs = new LinkedHashSet<>();
        int searchCount = hasSearches ? Math.min(requestedNeeds.size(), MAX_SEARCHES_PER_REQUEST) : 0;
        for (int index = 0; index < searchCount; index++) {
            JsonNode search = requestedNeeds.get(index);
            Set<SourceContentType> types = contentTypes(search.path("contentTypes"));
            String query = boundedQuery(search.path("query").asText(""));
            Map<String, MergedHit> searchHits = new LinkedHashMap<>();
            int directCount = 0;
            for (JsonNode sourceIdNode : search.path("sourceObjectIds")) {
                if (directCount++ >= 4) break;
                String sourceId = sourceIdNode.asText("").trim();
                if (!catalog.objects().containsKey(sourceId)) {
                    invalidSourceIdsBySearch.computeIfAbsent(index, ignored -> new ArrayList<>()).add(sourceId);
                    continue;
                }
                MergedHit direct = merged.computeIfAbsent(sourceId,
                        id -> new MergedHit(id, 1.10, new LinkedHashSet<>()));
                direct.score = Math.max(direct.score, 1.10);
                direct.searchIndexes.add(index);
                searchHits.put(sourceId, direct);
            }
            int refCount = 0;
            for (JsonNode ref : search.path("profileClaimRefs")) {
                if (refCount++ >= 4) break;
                String claimRef = ref.asText("").trim();
                if (!claimIndex.sources().containsKey(claimRef)) {
                    invalidClaimRefsBySearch.computeIfAbsent(index, ignored -> new ArrayList<>()).add(claimRef);
                    continue;
                }
                matchedProfileClaimRefs.add(claimRef);
                for (String sourceId : claimIndex.sources().getOrDefault(claimRef, Set.of())) {
                    MergedHit claimHit = merged.computeIfAbsent(sourceId,
                            id -> new MergedHit(id, 1.05, new LinkedHashSet<>()));
                    claimHit.score = Math.max(claimHit.score, 1.05);
                    claimHit.searchIndexes.add(index);
                    searchHits.put(sourceId, claimHit);
                }
            }
            if (query.isEmpty()) {
                hitsBySearch.put(index, new ArrayList<>(searchHits.values()));
                continue;
            }
            PageHint pageHint = pageHint(search);
            PaperSearchRequest request = new PaperSearchRequest(query, types, pageHint.start(), pageHint.end(),
                    MAX_SEARCH_RESULTS);
            String inferredClaimRef = evidenceFocus == null || evidenceFocus.isBlank()
                    ? "" : bestAnyClaimRef(evidenceFocus, claimIndex.statements(), 0.18);
            if (inferredClaimRef.isBlank()) inferredClaimRef = bestClaimRef(query, claimIndex.statements());
            if (!inferredClaimRef.isBlank()) matchedProfileClaimRefs.add(inferredClaimRef);
            for (String sourceId : claimIndex.sources().getOrDefault(inferredClaimRef, Set.of())) {
                MergedHit claimHit = merged.computeIfAbsent(sourceId,
                        id -> new MergedHit(id, 1.04, new LinkedHashSet<>()));
                claimHit.score = Math.max(claimHit.score, 1.04);
                claimHit.searchIndexes.add(index);
                searchHits.put(sourceId, claimHit);
            }
            mergeRetrievedHits(search(catalog, request, searchCache), index, merged, searchHits);
            if (searchHits.isEmpty()) {
                String fallbackQuery = fallbackQuery(search, query);
                Set<SourceContentType> fallbackTypes = types;
                if (fallbackQuery.equals(query) && !types.isEmpty()) fallbackTypes = Set.of();
                if (!fallbackQuery.isBlank()
                        && (!fallbackQuery.equals(query) || !fallbackTypes.equals(types))) {
                    PaperSearchRequest fallbackRequest = new PaperSearchRequest(fallbackQuery, fallbackTypes,
                            pageHint.start(), pageHint.end(), MAX_SEARCH_RESULTS);
                    mergeRetrievedHits(search(catalog, fallbackRequest, searchCache), index, merged, searchHits);
                }
            }
            hitsBySearch.put(index, new ArrayList<>(searchHits.values()));
        }

        FormulaExpansion formulaExpansion = expandFormulaFamilies(catalog, merged, hitsBySearch);
        Map<Integer, List<MergedHit>> candidatePages = new LinkedHashMap<>();
        Map<Integer, List<MergedHit>> eligibleHitsBySearch = new LinkedHashMap<>();
        Map<String, MergedHit> eligibleMerged = new LinkedHashMap<>();
        for (Map.Entry<Integer, List<MergedHit>> entry : hitsBySearch.entrySet()) {
            JsonNode need = requestedNeeds.get(entry.getKey());
            List<MergedHit> eligibleHits = entry.getValue().stream()
                    .filter(hit -> SourceEvidenceQuality.usableForCitation(
                            catalog.objects().get(hit.sourceObjectId)))
                    .toList();
            eligibleHits = restrictToExactFigureTargets(catalog, eligibleHits, need);
            eligibleHitsBySearch.put(entry.getKey(), eligibleHits);
            List<MergedHit> page = candidatePage(eligibleHits, need);
            candidatePages.put(entry.getKey(), page);
            page.forEach(hit -> eligibleMerged.putIfAbsent(hit.sourceObjectId, hit));
        }
        boolean hasExplicitTargets = hasSearches && hasTargets(requestedNeeds, searchCount);
        int sourceLimit = hasExplicitTargets || formulaExpansion.expanded()
                ? MAX_TARGETED_SOURCES_PER_RESULT : MAX_SOURCES_PER_RESULT;
        int requested = Math.max(1, Math.min(sourceLimit,
                args.path("maxEvidence").asInt(MAX_SOURCES_PER_RESULT)));
        requested = Math.min(sourceLimit, Math.max(requested, searchCount));
        if (formulaExpansion.expanded()) requested = sourceLimit;
        LinkedHashMap<String, MergedHit> selectedById = new LinkedHashMap<>();
        // Reserve the first available result for each independent evidence need before filling
        // the remaining slots globally. This prevents a high-scoring query from starving all
        // other searches in one batch response. Pagination is applied before this step, so a
        // continuation request cannot return the same first-page candidates again.
        for (List<MergedHit> searchHits : candidatePages.values()) {
            if (selectedById.size() >= requested) break;
            searchHits.stream()
                    .findFirst()
                    .ifPresent(hit -> selectedById.putIfAbsent(hit.sourceObjectId, hit));
        }
        eligibleMerged.values().stream()
                .filter(hit -> !selectedById.containsKey(hit.sourceObjectId))
                .limit(Math.max(0, requested - selectedById.size()))
                .forEach(hit -> selectedById.putIfAbsent(hit.sourceObjectId, hit));

        // Page-range reads fill only the slots left after Need coverage. A broad page request
        // must not consume the slots needed to expose the first candidate for an explicit Need;
        // otherwise the Need would report hasMore=true without a usable cursor continuation.
        if (hasPages && selectedById.size() < requested) {
            for (int rangeIndex = 0; rangeIndex < Math.min(pageRanges.size(), 2); rangeIndex++) {
                JsonNode range = pageRanges.get(rangeIndex);
                int startPage = range.path("startPage").asInt();
                int endPage = range.path("endPage").asInt();
                if (startPage <= 0 || endPage < startPage || endPage - startPage + 1 > MAX_PAGE_SPAN) {
                    continue;
                }
                for (SourceObject source : sourceService.readPages(
                        catalog, startPage, endPage, MAX_CONTENT_CHARACTERS)) {
                    if (!SourceEvidenceQuality.usableForCitation(source)) continue;
                    MergedHit hit = merged.computeIfAbsent(source.sourceObjectId(),
                            id -> new MergedHit(id, 0.85, new LinkedHashSet<>()));
                    selectedById.putIfAbsent(source.sourceObjectId(), hit);
                    if (selectedById.size() >= requested) break;
                }
                if (selectedById.size() >= requested) break;
            }
        }

        List<MergedHit> selected = new ArrayList<>();
        List<SourceObject> sources = new ArrayList<>();
        for (MergedHit hit : selectedById.values()) {
            SourceObject source = sourceService.readSource(catalog, hit.sourceObjectId);
            if (!SourceEvidenceQuality.usableForCitation(source)) continue;
            selected.add(hit);
            sources.add(source);
        }
        int perSource = Math.max(240, MAX_CONTENT_CHARACTERS / Math.max(1, sources.size()));
        Set<String> sourceIds = selected.stream().map(hit -> hit.sourceObjectId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        while (true) {
            List<Map<String, Object>> compact = new ArrayList<>();
            for (int index = 0; index < sources.size(); index++) {
                SourceObject source = sources.get(index);
                MergedHit hit = selected.get(index);
                Map<String, Object> value = compactSource(catalog, source, perSource);
                value.put("score", hit.score);
                value.put("matchedSearches", List.copyOf(hit.searchIndexes));
                compact.add(value);
            }
            try {
                String status = compact.isEmpty() ? "not_found" : "found";
                List<Map<String, Object>> evidenceNeeds = buildEvidenceNeeds(catalog, requestedNeeds,
                        hitsBySearch, candidatePages, eligibleHitsBySearch, selectedById,
                        invalidSourceIdsBySearch, invalidClaimRefsBySearch,
                        compact.stream().collect(java.util.stream.Collectors.toMap(
                                source -> String.valueOf(source.get("sourceObjectId")),
                                source -> Boolean.TRUE.equals(source.get("contentComplete")),
                                (left, right) -> left, LinkedHashMap::new)));
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("status", status);
                payload.put("untrustedPaperContent", true);
                payload.put("sources", compact);
                payload.put("evidenceNeeds", evidenceNeeds);
                payload.put("matchedProfileClaimRefs", List.copyOf(matchedProfileClaimRefs));
                payload.put("requestedNeeds", searchCount);
                payload.put("requestedPageRanges", hasPages ? Math.min(pageRanges.size(), 2) : 0);
                payload.put("usage", compact.isEmpty()
                        ? "当前确定性检索没有返回候选来源。请依据每个 Need 的 progress 决定是否进行一次有明确缺口的补检索。"
                        : "请阅读返回的原文判断是否支持答案。targetCoverage 只表示论文原文中的词面匹配；只针对仍未解决的 Need 补检索，不要把 retrievalStatus 当成语义结论。");
                return result(payload, sourceIds);
            } catch (IllegalStateException oversized) {
                if (perSource <= 240) throw oversized;
                perSource = Math.max(240, perSource * 3 / 4);
            }
        }
    }

    private FormulaExpansion expandFormulaFamilies(PaperSourceCatalog catalog,
                                                    Map<String, MergedHit> merged,
                                                    Map<Integer, List<MergedHit>> hitsBySearch) {
        Map<String, List<SourceObject>> families = new LinkedHashMap<>();
        for (SourceObject source : catalog.objects().values()) {
            String base = subformulaBase(source);
            if (base != null) families.computeIfAbsent(base, ignored -> new ArrayList<>()).add(source);
        }
        boolean expanded = false;
        for (Map.Entry<Integer, List<MergedHit>> entry : hitsBySearch.entrySet()) {
            List<MergedHit> searchHits = entry.getValue();
            List<MergedHit> originalHits = List.copyOf(searchHits);
            Set<String> present = searchHits.stream().map(hit -> hit.sourceObjectId)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            for (MergedHit hit : originalHits) {
                SourceObject source = catalog.objects().get(hit.sourceObjectId);
                String base = subformulaBase(source);
                List<SourceObject> siblings = base == null ? List.of() : families.getOrDefault(base, List.of());
                if (siblings.size() < 2) continue;
                for (SourceObject sibling : siblings) {
                    MergedHit siblingHit = merged.computeIfAbsent(sibling.sourceObjectId(),
                            id -> new MergedHit(id, Math.max(0.01, hit.score - 0.01), new LinkedHashSet<>()));
                    siblingHit.score = Math.max(siblingHit.score, Math.max(0.01, hit.score - 0.01));
                    siblingHit.searchIndexes.add(entry.getKey());
                    if (present.add(sibling.sourceObjectId())) {
                        searchHits.add(siblingHit);
                        if (!sibling.sourceObjectId().equals(hit.sourceObjectId)) expanded = true;
                    }
                }
            }
        }
        return new FormulaExpansion(expanded);
    }

    private static String subformulaBase(SourceObject source) {
        if (source == null || source.contentType() != SourceContentType.FORMULA) return null;
        Matcher matcher = SUBFORMULA_NUMBER.matcher(SourceObject.normalize(source.formulaNumber()));
        return matcher.matches() ? matcher.group(1) : null;
    }

    private List<MergedHit> candidatePage(List<MergedHit> hits, JsonNode need) {
        if (hits == null || hits.isEmpty()) return List.of();
        int offset = need == null ? 0 : Math.max(0, need.path("cursor").asInt(0));
        List<MergedHit> ordered = hits.stream()
                .sorted(Comparator.comparingDouble((MergedHit hit) -> hit.score).reversed()
                        .thenComparing(hit -> hit.sourceObjectId))
                .toList();
        if (offset >= ordered.size()) return List.of();
        int end = Math.min(ordered.size(), offset + MAX_SEARCH_RESULTS);
        return ordered.subList(offset, end);
    }

    private List<Map<String, Object>> buildEvidenceNeeds(PaperSourceCatalog catalog,
                                                           JsonNode requestedNeeds,
                                                           Map<Integer, List<MergedHit>> hitsBySearch,
                                                           Map<Integer, List<MergedHit>> candidatePages,
                                                           Map<Integer, List<MergedHit>> eligibleHitsBySearch,
                                                           Map<String, MergedHit> selectedById,
                                                           Map<Integer, List<String>> invalidSourceIdsBySearch,
                                                           Map<Integer, List<String>> invalidClaimRefsBySearch,
                                                           Map<String, Boolean> contentCompleteBySourceId) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<Integer, List<MergedHit>> entry : hitsBySearch.entrySet()) {
            JsonNode need = requestedNeeds.get(entry.getKey());
            String id = needId(need, entry.getKey());
            List<MergedHit> candidatePage = candidatePages.getOrDefault(entry.getKey(), List.of());
            List<String> returnedIds = candidatePage.stream().map(hit -> hit.sourceObjectId)
                    .filter(selectedById::containsKey).toList();
            int candidateOffset = need == null ? 0 : Math.max(0, need.path("cursor").asInt(0));
            List<MergedHit> eligibleHits = eligibleHitsBySearch.getOrDefault(entry.getKey(), List.of());
            List<String> targets = targets(need);
            List<String> matchedTargets = targets.stream().filter(target -> returnedIds.stream()
                    .map(catalog::requireObject).anyMatch(source -> targetMatches(source, target))).toList();
            List<String> missingTargets = targets.stream()
                    .filter(target -> !matchedTargets.contains(target)).toList();
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("needId", id);
            value.put("objective", need.path("objective").asText(""));
            value.put("searchIndex", entry.getKey());
            value.put("retrievalStatus", returnedIds.isEmpty()
                    ? candidatePage.isEmpty() ? "not_found" : "deferred"
                    : "found");
            value.put("sourceObjectIds", returnedIds);
            value.put("candidateCount", eligibleHits.size());
            value.put("returnedCount", returnedIds.size());
            // Advance to the first candidate in this page that was not exposed. A global
            // maxEvidence cap can select non-contiguous candidates shared with another Need;
            // counting returned IDs would then skip an unseen candidate in the middle.
            int nextCandidate = nextCandidateCursor(candidatePage, selectedById.keySet(),
                    candidateOffset);
            boolean hasMore = nextCandidate < eligibleHits.size();
            value.put("hasMore", hasMore);
            if (hasMore) {
                value.put("nextCursor", nextCandidate);
            }
            value.put("targetCoverage", Map.of(
                    "matchedTargets", matchedTargets,
                    "missingTargets", missingTargets));
            value.put("coverageState", coverageState(returnedIds, targets, missingTargets, hasMore));
            if (!returnedIds.isEmpty()) {
                value.put("contentComplete", returnedIds.stream()
                        .allMatch(sourceId -> contentCompleteBySourceId.getOrDefault(sourceId, false)));
            }
            value.put("invalidSourceObjectIds",
                    invalidSourceIdsBySearch.getOrDefault(entry.getKey(), List.of()));
            value.put("invalidProfileClaimRefs",
                    invalidClaimRefsBySearch.getOrDefault(entry.getKey(), List.of()));
            result.add(value);
        }
        return result;
    }

    private static String coverageState(List<String> returnedIds, List<String> targets,
                                        List<String> missingTargets, boolean hasMore) {
        if (returnedIds.isEmpty()) return hasMore ? "DEFERRED" : "NO_MATCH";
        if (targets.isEmpty()) return "SOURCES_AVAILABLE";
        if (missingTargets.isEmpty()) return "LEXICAL_TARGETS_COVERED";
        return hasMore ? "LEXICAL_TARGETS_PARTIAL_MORE" : "LEXICAL_TARGETS_PARTIAL";
    }

    private List<RetrievalHit> search(PaperSourceCatalog catalog, PaperSearchRequest request,
                                      Map<SearchRequestKey, List<RetrievalHit>> cache) {
        SearchRequestKey key = SearchRequestKey.from(request);
        return cache.computeIfAbsent(key, ignored -> sourceService.search(catalog, request));
    }

    private int nextCandidateCursor(List<MergedHit> page, Set<String> selectedIds, int offset) {
        int cursor = offset;
        for (MergedHit hit : page) {
            if (!selectedIds.contains(hit.sourceObjectId)) return cursor;
            cursor++;
        }
        return offset + page.size();
    }

    private void mergeRetrievedHits(List<RetrievalHit> hits, int searchIndex,
                                    Map<String, MergedHit> merged,
                                    Map<String, MergedHit> searchHits) {
        if (hits == null) return;
        for (RetrievalHit hit : hits) {
            MergedHit previous = merged.get(hit.sourceObjectId());
            if (previous == null) {
                previous = new MergedHit(hit.sourceObjectId(), hit.score(), new LinkedHashSet<>());
                merged.put(hit.sourceObjectId(), previous);
            } else {
                previous.score = Math.max(previous.score, hit.score());
            }
            previous.searchIndexes.add(searchIndex);
            searchHits.put(hit.sourceObjectId(), previous);
        }
    }

    private String fallbackQuery(JsonNode need, String primaryQuery) {
        List<String> keywords = new ArrayList<>();
        JsonNode keywordNode = need.path("keywords");
        if (keywordNode.isArray()) {
            keywordNode.forEach(keyword -> {
                String value = boundedQuery(keyword.asText(""));
                if (!value.isBlank()) keywords.add(value);
            });
        }
        if (!keywords.isEmpty()) return boundedQuery(String.join(" ", keywords));
        return boundedQuery(need.path("sectionHint").asText(primaryQuery));
    }

    private PageHint pageHint(JsonNode need) {
        JsonNode hints = need.path("pageHints");
        if (!hints.isArray() || hints.isEmpty()) return new PageHint(null, null);
        int start = hints.get(0).asInt(0);
        if (start < 1) return new PageHint(null, null);
        int end = hints.size() > 1 ? hints.get(1).asInt(start) : start;
        if (end < start) end = start;
        end = Math.min(end, start + MAX_PAGE_SPAN - 1);
        return new PageHint(start, end);
    }

    private static String needId(JsonNode need, int index) {
        String id = need == null ? "" : need.path("id").asText("").trim();
        return id.isBlank() ? "need-" + index : id;
    }

    private List<Map<String, Object>> validateEvidenceRequest(JsonNode args) {
        List<Map<String, Object>> issues = new ArrayList<>();
        if (args == null || !args.isObject()) {
            issues.add(issue(null, "request", "INVALID_REQUEST_OBJECT", "证据检索参数必须是 JSON 对象"));
            return issues;
        }
        Set<String> rootFields = Set.of("needs", "pageRanges", "maxEvidence", "_evidenceFocus");
        args.fieldNames().forEachRemaining(field -> {
            if (!rootFields.contains(field)) {
                issues.add(issue(null, field, "UNKNOWN_FIELD", "证据检索请求包含未知字段：" + field));
            }
        });

        JsonNode needs = args.get("needs");
        JsonNode pageRanges = args.get("pageRanges");
        boolean hasNeeds = needs != null && needs.isArray() && !needs.isEmpty();
        boolean hasPages = pageRanges != null && pageRanges.isArray() && !pageRanges.isEmpty();
        if (!hasNeeds && !hasPages) {
            issues.add(issue(null, "request", "MISSING_REQUEST_CONTENT", "至少需要一个证据需求或页码范围"));
        }
        if (needs != null) {
            if (!needs.isArray()) {
                issues.add(issue(null, "needs", "INVALID_ARRAY", "needs 必须是数组"));
            } else if (needs.size() > MAX_SEARCHES_PER_REQUEST) {
                issues.add(issue(null, "needs", "TOO_MANY_NEEDS",
                        "一次最多允许 " + MAX_SEARCHES_PER_REQUEST + " 个证据需求"));
            } else {
                validateNeeds(needs, issues);
            }
        }
        if (pageRanges != null) validatePageRanges(pageRanges, issues);
        if (args.has("maxEvidence") && (!args.path("maxEvidence").isIntegralNumber()
                || args.path("maxEvidence").asInt() < 1 || args.path("maxEvidence").asInt() > 8)) {
            issues.add(issue(null, "maxEvidence", "INVALID_RANGE", "maxEvidence 必须是 1～8 的整数"));
        }
        return issues;
    }

    private void validateNeeds(JsonNode needs, List<Map<String, Object>> issues) {
        Set<String> ids = new LinkedHashSet<>();
        Set<String> allowedFields = Set.of("id", "objective", "query", "keywords", "targets",
                "sectionHint", "pageHints", "cursor", "profileClaimRefs", "contentTypes", "sourceObjectIds",
                "includeVisual", "refinementReason");
        for (int index = 0; index < needs.size(); index++) {
            JsonNode need = needs.get(index);
            String fallbackId = "need-" + index;
            if (need == null || !need.isObject()) {
                issues.add(issue(fallbackId, "need", "INVALID_NEED_OBJECT", "每个证据需求都必须是 JSON 对象"));
                continue;
            }
            need.fieldNames().forEachRemaining(field -> {
                if (!allowedFields.contains(field)) {
                    issues.add(issue(fallbackId, field, "UNKNOWN_FIELD", "证据需求包含未知字段：" + field));
                }
            });
            String id = requiredText(need, "id", 40, fallbackId, issues);
            String needId = id.isBlank() ? fallbackId : id;
            if (!id.isBlank() && !ids.add(id)) {
                issues.add(issue(needId, "id", "DUPLICATE_NEED_ID", "证据需求的 id 必须唯一"));
            }
            requiredText(need, "objective", 240, needId, issues);
            String query = optionalText(need, "query", MAX_QUERY_CHARACTERS, needId, issues);
            optionalText(need, "sectionHint", 120, needId, issues);
            optionalText(need, "refinementReason", 240, needId, issues);
            validateCursor(need, needId, issues);
            stringArray(need, "keywords", 8, 120, needId, issues, null);
            stringArray(need, "targets", 8, 120, needId, issues, null);
            List<String> claimRefs = stringArray(need, "profileClaimRefs", 4, 40, needId, issues, null);
            List<String> sourceIds = stringArray(need, "sourceObjectIds", 4, 160, needId, issues, null);
            stringArray(need, "contentTypes", 5, 20, needId, issues,
                    Set.of("TEXT", "FORMULA", "TABLE", "FIGURE", "ALGORITHM"));
            validatePageHints(need, needId, issues);
            if (need.has("includeVisual") && !need.path("includeVisual").isBoolean()) {
                issues.add(issue(needId, "includeVisual", "INVALID_BOOLEAN", "includeVisual 必须是布尔值"));
            }
            if (query.isBlank() && claimRefs.isEmpty() && sourceIds.isEmpty()) {
                issues.add(issue(needId, "query", "MISSING_RETRIEVAL_ANCHOR",
                        "该证据需求至少需要 query、sourceObjectIds 或 profileClaimRefs 之一"));
            }
        }
    }

    private void validateCursor(JsonNode need, String needId, List<Map<String, Object>> issues) {
        if (!need.has("cursor")) return;
        JsonNode cursor = need.path("cursor");
        if (!cursor.isIntegralNumber() || cursor.asInt() < 0 || cursor.asInt() > 10_000) {
            issues.add(issue(needId, "cursor", "INVALID_CURSOR",
                    "cursor 必须是 0～10000 的非负整数，并且只能使用上一次返回的 nextCursor"));
        }
    }

    private void validatePageRanges(JsonNode ranges, List<Map<String, Object>> issues) {
        if (!ranges.isArray()) {
            issues.add(issue(null, "pageRanges", "INVALID_ARRAY", "pageRanges 必须是数组"));
            return;
        }
        if (ranges.isEmpty() || ranges.size() > 2) {
            issues.add(issue(null, "pageRanges", "INVALID_ARRAY_SIZE", "pageRanges 必须包含 1～2 个范围"));
        }
        for (int index = 0; index < ranges.size(); index++) {
            JsonNode range = ranges.get(index);
            if (!range.isObject()) {
                issues.add(issue(null, "pageRanges", "INVALID_PAGE_RANGE", "每个页码范围都必须是 JSON 对象"));
                continue;
            }
            int start = range.path("startPage").asInt(0);
            int end = range.path("endPage").asInt(0);
            if (start < 1 || end < start || end - start + 1 > MAX_PAGE_SPAN) {
                issues.add(issue(null, "pageRanges", "INVALID_PAGE_RANGE", "每个页码范围必须是最多连续两页的正整数范围"));
            }
        }
    }

    private void validatePageHints(JsonNode need, String needId, List<Map<String, Object>> issues) {
        if (!need.has("pageHints")) return;
        JsonNode hints = need.path("pageHints");
        if (!hints.isArray() || hints.isEmpty() || hints.size() > 2) {
            issues.add(issue(needId, "pageHints", "INVALID_ARRAY_SIZE", "pageHints 必须包含 1～2 个正整数页码"));
            return;
        }
        Set<Integer> seen = new LinkedHashSet<>();
        for (JsonNode hint : hints) {
            if (!hint.canConvertToInt() || hint.asInt() < 1 || !seen.add(hint.asInt())) {
                issues.add(issue(needId, "pageHints", "INVALID_PAGE_HINT", "pageHints 必须是互不重复的正整数页码"));
                return;
            }
        }
    }

    private String requiredText(JsonNode node, String field, int maxLength, String needId,
                                List<Map<String, Object>> issues) {
        if (!node.has(field)) {
            issues.add(issue(needId, field, "MISSING_REQUIRED_FIELD", field + " 为必填字段"));
            return "";
        }
        return optionalText(node, field, maxLength, needId, issues);
    }

    private String optionalText(JsonNode node, String field, int maxLength, String needId,
                                List<Map<String, Object>> issues) {
        if (!node.has(field)) return "";
        JsonNode valueNode = node.get(field);
        if (!valueNode.isTextual()) {
            issues.add(issue(needId, field, "INVALID_TEXT", field + " 必须是字符串"));
            return "";
        }
        String value = valueNode.asText().trim();
        if (value.isBlank()) {
            issues.add(issue(needId, field, "BLANK_TEXT", field + " 不能为空"));
        } else if (value.length() > maxLength) {
            issues.add(issue(needId, field, "TEXT_TOO_LONG", field + " 超过最大长度 " + maxLength));
        }
        return value;
    }

    private List<String> stringArray(JsonNode node, String field, int maxItems, int maxLength,
                                     String needId, List<Map<String, Object>> issues,
                                     Set<String> allowedValues) {
        if (!node.has(field)) return List.of();
        JsonNode values = node.path(field);
        if (!values.isArray()) {
            issues.add(issue(needId, field, "INVALID_ARRAY", field + " 必须是数组"));
            return List.of();
        }
        if (values.isEmpty() || values.size() > maxItems) {
            issues.add(issue(needId, field, "INVALID_ARRAY_SIZE",
                    field + " 必须包含 1～" + maxItems + " 个元素"));
        }
        List<String> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode item : values) {
            if (!item.isTextual()) {
                issues.add(issue(needId, field, "INVALID_ARRAY_ITEM", field + " 的元素必须是字符串"));
                continue;
            }
            String value = item.asText().trim();
            String comparison = allowedValues == null ? value.toLowerCase(Locale.ROOT)
                    : value.toUpperCase(Locale.ROOT);
            if (value.isBlank()) {
                issues.add(issue(needId, field, "BLANK_ARRAY_ITEM", field + " 不能包含空字符串"));
            } else if (value.length() > maxLength) {
                issues.add(issue(needId, field, "ARRAY_ITEM_TOO_LONG", field + " 的元素超过最大长度 " + maxLength));
            } else if (allowedValues != null && !allowedValues.contains(comparison)) {
                issues.add(issue(needId, field, "INVALID_ENUM_VALUE", field + " 包含不支持的值：" + value));
            } else if (!seen.add(comparison)) {
                issues.add(issue(needId, field, "DUPLICATE_ARRAY_ITEM", field + " 不能包含重复元素"));
            } else {
                result.add(value);
            }
        }
        return result;
    }

    private AgentToolExecution invalidRequest(List<Map<String, Object>> issues) {
        try {
            return result(Map.of(
                    "status", "invalid_request",
                    "sources", List.of(),
                    "evidenceNeeds", List.of(),
                    "issues", issues,
                    "usage", "请根据 issues 修正证据需求后再调用；不要把输入错误解释为论文没有证据。"
            ), Set.of());
        } catch (Exception error) {
            throw new IllegalStateException("无法生成证据检索输入错误", error);
        }
    }

    private static Map<String, Object> issue(String needId, String field, String code, String message) {
        Map<String, Object> issue = new LinkedHashMap<>();
        if (needId != null && !needId.isBlank()) issue.put("needId", needId);
        issue.put("field", field);
        issue.put("code", code);
        issue.put("message", message);
        return issue;
    }

    private static boolean hasTargets(JsonNode needs, int count) {
        for (int index = 0; index < count; index++) {
            if (!targets(needs.get(index)).isEmpty()) return true;
        }
        return false;
    }

    private static List<String> targets(JsonNode need) {
        if (need == null || !need.path("targets").isArray()) return List.of();
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (JsonNode target : need.path("targets")) {
            String value = SourceObject.normalize(target.asText(""));
            if (!value.isBlank()) values.add(value);
            if (values.size() >= 8) break;
        }
        return List.copyOf(values);
    }

    private static boolean targetMatches(SourceObject source, String target) {
        String normalizedTarget = SourceObject.normalize(target).toLowerCase(Locale.ROOT);
        if (source.contentType() == SourceContentType.FIGURE) {
            String requestedFigureNumber = figureNumberFromTarget(normalizedTarget);
            String actualFigureNumber = figureNumberOf(source);
            if (!requestedFigureNumber.isBlank() && requestedFigureNumber.equals(actualFigureNumber)) return true;
        }
        String formulaNumber = SourceObject.normalize(source.formulaNumber()).toLowerCase(Locale.ROOT);
        Matcher formulaTargetMatcher = FORMULA_TARGET.matcher(normalizedTarget);
        if (source.contentType() == SourceContentType.FORMULA && formulaTargetMatcher.matches()) {
            String requestedNumber = formulaTargetMatcher.group(1).toLowerCase(Locale.ROOT);
            return java.util.Arrays.stream(formulaNumber.split(","))
                    .map(String::strip)
                    .anyMatch(requestedNumber::equals);
        }
        String formulaTarget = normalizedTarget.replaceAll("^[\\(\\[]|[\\)\\]]$", "");
        if (!formulaNumber.isBlank() && formulaNumber.equals(formulaTarget)) return true;
        String searchable = (source.normalizedContent() + " "
                + String.join(" ", source.sectionPath()) + " " + source.formulaNumber())
                .toLowerCase(Locale.ROOT);
        if (searchable.contains(normalizedTarget)) return true;
        return !formulaTarget.equals(normalizedTarget) && searchable.contains(formulaTarget);
    }

    private List<MergedHit> restrictToExactFigureTargets(PaperSourceCatalog catalog,
                                                          List<MergedHit> hits,
                                                          JsonNode need) {
        if (hits.isEmpty() || need == null || !isFigureOnlyNeed(need)) return hits;
        Set<String> requestedNumbers = figureNumbersForNeed(need);
        if (requestedNumbers.isEmpty()) return hits;
        List<MergedHit> exact = hits.stream()
                .filter(hit -> {
                    SourceObject source = catalog.objects().get(hit.sourceObjectId);
                    return source != null && figureNumberOf(source) != null
                            && requestedNumbers.contains(figureNumberOf(source));
                })
                .toList();
        // Preserve fallback retrieval when the parser has no numbered FIGURE
        // object.  Once an exact object exists, unrelated figures are not useful
        // evidence for this explicit Need and must not receive visual crops.
        return exact.isEmpty() ? hits : exact;
    }

    private boolean isFigureOnlyNeed(JsonNode need) {
        JsonNode contentTypes = need.path("contentTypes");
        if (!contentTypes.isArray() || contentTypes.size() != 1) return false;
        return "FIGURE".equalsIgnoreCase(contentTypes.get(0).asText());
    }

    private Set<String> figureNumbersForNeed(JsonNode need) {
        Set<String> numbers = new LinkedHashSet<>();
        if (need.path("targets").isArray()) {
            for (JsonNode target : need.path("targets")) {
                String number = figureNumberFromTarget(target.asText(""));
                if (!number.isBlank()) numbers.add(number);
            }
        }
        String queryNumber = figureNumberFromTarget(need.path("query").asText(""));
        if (!queryNumber.isBlank()) numbers.add(queryNumber);
        return numbers;
    }

    private static String figureNumberOf(SourceObject source) {
        if (source == null || source.contentType() != SourceContentType.FIGURE) return "";
        String declared = source.provenance().getOrDefault("figureNumber", "").strip();
        if (!declared.isBlank()) return declared.toLowerCase(Locale.ROOT);
        Matcher matcher = FIGURE_REFERENCE.matcher(source.rawContent() == null ? "" : source.rawContent());
        return matcher.find() ? matcher.group(1).toLowerCase(Locale.ROOT) : "";
    }

    private static String figureNumberFromTarget(String value) {
        String normalized = SourceObject.normalize(value == null ? "" : value).toLowerCase(Locale.ROOT).strip();
        Matcher matcher = FIGURE_REFERENCE.matcher(normalized);
        if (matcher.find()) return matcher.group(1).toLowerCase(Locale.ROOT);
        return normalized.matches("[0-9]{1,4}[a-z]?") ? normalized : "";
    }

    private ProfileClaimIndex profileClaimIndex(PaperSourceCatalog catalog) {
        if (memoryMapper == null || catalog == null) return ProfileClaimIndex.EMPTY;
        PaperMemoryRecord memory = memoryMapper.selectLatest(catalog.paperId());
        if (memory == null || memory.getProfileJson() == null
                || !catalog.documentHash().equals(memory.getDocumentHash())
                || !PaperUnderstandingService.PIPELINE_VERSION.equals(memory.getUnderstandingVersion())
                || !catalog.parserVersion().equals(memory.getLayoutParserVersion())) return ProfileClaimIndex.EMPTY;
        try {
            PaperGlobalProfile profile = objectMapper.readValue(memory.getProfileJson(), PaperGlobalProfile.class);
            Map<String, Set<String>> sources = new LinkedHashMap<>();
            Map<String, String> statements = new LinkedHashMap<>();
            addClaims(sources, statements, "contribution", profile.coreContributions(), catalog);
            addClaims(sources, statements, "finding", profile.keyFindings(), catalog);
            addClaims(sources, statements, "limitation", profile.limitations(), catalog);
            for (int index = 0; index < profile.benchmarkResults().size(); index++) {
                PaperGlobalProfile.BenchmarkResult benchmark = profile.benchmarkResults().get(index);
                String ref = "benchmark:" + index;
                sources.put(ref, sourceIdsForBlocks(catalog, benchmark.evidenceBlockIds()));
                statements.put(ref, String.join(" ", benchmark.metric(), benchmark.value(),
                        benchmark.baseline(), benchmark.dataset()));
            }
            return new ProfileClaimIndex(sources, statements);
        } catch (Exception ignored) {
            return ProfileClaimIndex.EMPTY;
        }
    }

    private void addClaims(Map<String, Set<String>> sources, Map<String, String> statements,
                           String prefix, List<PaperMemoryClaim> claims, PaperSourceCatalog catalog) {
        for (int index = 0; index < claims.size(); index++) {
            String ref = prefix + ":" + index;
            sources.put(ref, sourceIdsForBlocks(catalog, claims.get(index).evidenceBlockIds()));
            statements.put(ref, claims.get(index).statement());
        }
    }

    private String bestClaimRef(String query, Map<String, String> statements) {
        // Models sometimes append long generic retrieval vocabulary. The leading phrase carries
        // the actual evidence need and avoids a generic profile claim winning by token volume.
        String focusedQuery = query == null ? "" : query.substring(0, Math.min(160, query.length()));
        String bestFindingRef = "";
        double bestFindingScore = 0;
        String bestOtherRef = "";
        double bestOtherScore = 0;
        for (Map.Entry<String, String> entry : statements.entrySet()) {
            double score = characterBigramCoverage(entry.getValue(), focusedQuery);
            if (entry.getKey().startsWith("finding:")) {
                if (score > bestFindingScore) {
                    bestFindingScore = score;
                    bestFindingRef = entry.getKey();
                }
            } else if (score > bestOtherScore) {
                bestOtherScore = score;
                bestOtherRef = entry.getKey();
            }
        }
        if (bestFindingScore >= 0.22) return bestFindingRef;
        return bestOtherScore >= 0.22 ? bestOtherRef : "";
    }

    private String bestAnyClaimRef(String query, Map<String, String> statements, double minimumScore) {
        String bestFindingRef = "";
        double bestFindingScore = 0;
        String bestOtherRef = "";
        double bestOtherScore = 0;
        for (Map.Entry<String, String> entry : statements.entrySet()) {
            double score = characterBigramCoverage(entry.getValue(), query);
            if (entry.getKey().startsWith("finding:")) {
                if (score > bestFindingScore) {
                    bestFindingScore = score;
                    bestFindingRef = entry.getKey();
                }
            } else if (score > bestOtherScore) {
                bestOtherScore = score;
                bestOtherRef = entry.getKey();
            }
        }
        if (bestFindingScore >= minimumScore) return bestFindingRef;
        return bestOtherScore >= minimumScore ? bestOtherRef : "";
    }

    private double characterBigramCoverage(String claim, String query) {
        String normalizedClaim = normalizeForClaimMatching(claim);
        String normalizedQuery = normalizeForClaimMatching(query);
        if (normalizedClaim.length() < 2 || normalizedQuery.length() < 2) return 0;
        Set<String> claimCharacters = new LinkedHashSet<>();
        for (int index = 0; index < normalizedClaim.length(); index++) {
            claimCharacters.add(normalizedClaim.substring(index, index + 1));
        }
        long matchedCharacters = claimCharacters.stream().filter(normalizedQuery::contains).count();
        double characterCoverage = claimCharacters.isEmpty() ? 0
                : (double) matchedCharacters / claimCharacters.size();
        Set<String> claimBigrams = new LinkedHashSet<>();
        for (int index = 0; index < normalizedClaim.length() - 1; index++) {
            claimBigrams.add(normalizedClaim.substring(index, index + 2));
        }
        long matchedBigrams = claimBigrams.stream().filter(normalizedQuery::contains).count();
        double bigramCoverage = claimBigrams.isEmpty() ? 0
                : (double) matchedBigrams / claimBigrams.size();
        return characterCoverage * 0.65 + bigramCoverage * 0.35;
    }

    /**
     * Align the small set of Chinese/English variants that appear in the
     * current paper corpus without introducing a general semantic matcher.
     */
    private String normalizeForClaimMatching(String value) {
        if (value == null) return "";
        return value.toLowerCase(Locale.ROOT)
                .replace("block error rate", "errorrate")
                .replace("bit error rate", "errorrate")
                .replace("error rate", "errorrate")
                .replace("codeword length", "blocklength")
                .replace("block length", "blocklength")
                .replace("误块率", "errorrate")
                .replace("误码率", "errorrate")
                .replace("错误率", "errorrate")
                .replace("bler", "errorrate")
                .replace("代码长度", "blocklength")
                .replace("码长", "blocklength")
                .replace("块长", "blocklength")
                .replace("较短", "short")
                .replace("更短", "short")
                .replace("缩短", "short")
                .replace("较低", "lower")
                .replace("更低", "lower")
                .replace("降低", "lower")
                .replaceAll("[^\\p{IsHan}\\p{IsLatin}\\p{N}%]+", "");
    }

    private Set<String> sourceIdsForBlocks(PaperSourceCatalog catalog, List<String> blockIds) {
        Set<String> required = new LinkedHashSet<>(blockIds);
        Set<String> result = new LinkedHashSet<>();
        for (SourceObject source : catalog.objects().values()) {
            Set<String> actual = new LinkedHashSet<>();
            String many = source.provenance().getOrDefault("blockIds", "");
            if (!many.isBlank()) actual.addAll(List.of(many.split(",")));
            String one = source.provenance().getOrDefault("blockId", "");
            if (!one.isBlank()) actual.add(one);
            if (actual.stream().anyMatch(required::contains)) result.add(source.sourceObjectId());
        }
        return result;
    }

    private record ProfileClaimIndex(Map<String, Set<String>> sources, Map<String, String> statements) {
        private static final ProfileClaimIndex EMPTY = new ProfileClaimIndex(Map.of(), Map.of());
    }

    private AgentToolExecution readPages(PaperSourceCatalog catalog, JsonNode args) throws Exception {
        int startPage = args.path("startPage").asInt();
        int endPage = args.path("endPage").asInt();
        if (endPage - startPage + 1 > MAX_PAGE_SPAN) {
            throw new IllegalArgumentException("read_pages 最多接受连续两页");
        }
        List<SourceObject> sources = sourceService.readPages(catalog, startPage,
                endPage, Math.min(MAX_CONTENT_CHARACTERS,
                        Math.max(1_000, args.path("maxCharacters").asInt(MAX_CONTENT_CHARACTERS))));
        return sourcesResult(catalog, sources);
    }

    private AgentToolExecution sourcesResult(PaperSourceCatalog catalog, List<SourceObject> sources) throws Exception {
        List<SourceObject> usable = sources.stream()
                .filter(SourceEvidenceQuality::usableForCitation).toList();
        List<SourceObject> bounded = usable.stream().limit(MAX_SOURCES_PER_RESULT).toList();
        Set<String> ids = bounded.stream().map(SourceObject::sourceObjectId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        int perSource = Math.max(240, MAX_CONTENT_CHARACTERS / Math.max(1, bounded.size()));
        List<Map<String, Object>> compact = bounded.stream()
                .map(source -> compactSource(catalog, source, perSource)).toList();
        return result(Map.of("untrustedPaperContent", true, "sources", compact,
                "truncated", usable.size() > bounded.size()), ids);
    }

    private AgentToolExecution result(Object value, Set<String> ids) throws Exception {
        String json = objectMapper.writeValueAsString(value);
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_RESULT_BYTES) {
            throw new IllegalStateException("工具结果超过模型负载上限");
        }
        return new AgentToolExecution(json, ids);
    }

    private Map<String, Object> compactSource(PaperSourceCatalog catalog, SourceObject source, int maxCharacters) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("sourceObjectId", source.sourceObjectId());
        value.put("contentType", source.contentType());
        value.put("evidenceQuality", SourceEvidenceQuality.status(source));
        value.put("citable", SourceEvidenceQuality.usableForCitation(source));
        Object sourceRole = source.provenance().get("role");
        if (sourceRole != null && !sourceRole.toString().isBlank()) value.put("sourceRole", sourceRole);
        if (catalog != null) {
            value.put("page", catalog.requireLocators(source.sourceObjectId()).get(0).pageNumber());
        }
        value.put("sectionPath", source.sectionPath());
        if (!source.formulaNumber().isBlank()) value.put("formulaNumber", source.formulaNumber());
        copyProvenanceText(value, source, "figureNumber");
        copyProvenanceText(value, source, "captionOf");
        copyProvenanceList(value, source, "discussesFigure");
        copyProvenanceList(value, source, "discussesFigureNumbers");
        copyProvenanceList(value, source, "discussedBy");
        if (source.provenance().containsKey("discussesFigure")
                || source.provenance().containsKey("discussedBy")) {
            value.put("relationUsage",
                    "关系字段中的来源 ID 仅用于定位，不代表本次已读取或可引用；引用关联来源前必须用 sourceObjectIds 重新读取。只有本结果 sources 中实际返回的来源才可绑定到 submit_answer。");
        }
        String textFormat = source.provenance().getOrDefault("textFormat", "PLAIN_TEXT");
        boolean textReliable = Boolean.parseBoolean(source.provenance().getOrDefault("textReliable",
                Boolean.toString(source.contentType() != SourceContentType.FORMULA)));
        value.put("textFormat", textFormat);
        value.put("textReliable", textReliable);
        String content = !textReliable && source.contentType() == SourceContentType.FORMULA
                ? formulaLabel(source) : truncate(source.rawContent(), maxCharacters);
        // Keep `content` as the compact compatibility field, but expose the
        // contract name used by the Skill explicitly.  A source unit is never
        // silently presented as complete when the response had to truncate it.
        value.put("fullText", content);
        value.put("content", content);
        boolean truncated = textReliable && source.rawContent().length() > maxCharacters;
        value.put("contentComplete", !truncated);
        value.put("truncated", truncated);
        return value;
    }

    private void copyProvenanceText(Map<String, Object> target, SourceObject source, String field) {
        String value = source.provenance().getOrDefault(field, "").strip();
        if (!value.isBlank()) target.put(field, value);
    }

    private void copyProvenanceList(Map<String, Object> target, SourceObject source, String field) {
        List<String> values = java.util.Arrays.stream(
                        source.provenance().getOrDefault(field, "").split(","))
                .map(String::strip).filter(value -> !value.isBlank()).distinct().limit(8).toList();
        if (!values.isEmpty()) target.put(field, values);
    }

    private static String formulaLabel(SourceObject source) {
        return source.formulaNumber().isBlank() ? "公式区域" : "公式 (" + source.formulaNumber() + ")";
    }

    private static String truncate(String value, int maxCharacters) {
        if (value == null || value.length() <= maxCharacters) return value == null ? "" : value;
        return value.substring(0, Math.max(0, maxCharacters - 1)) + "…";
    }

    private static String boundedQuery(String value) {
        if (value == null) return "";
        String normalized = value.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= MAX_QUERY_CHARACTERS) return normalized;
        return normalized.substring(0, MAX_QUERY_CHARACTERS).trim();
    }

    private static Set<SourceContentType> contentTypes(JsonNode node) {
        Set<SourceContentType> types = new LinkedHashSet<>();
        if (node != null && node.isArray()) {
            node.forEach(item -> {
                try {
                    types.add(SourceContentType.valueOf(item.asText().toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException ignored) {
                    // The schema already guides the model; ignore an unknown optional filter
                    // instead of failing the whole evidence batch.
                }
            });
        }
        return types;
    }

    private static final class MergedHit {
        private final String sourceObjectId;
        private double score;
        private final Set<Integer> searchIndexes;

        private MergedHit(String sourceObjectId, double score, Set<Integer> searchIndexes) {
            this.sourceObjectId = sourceObjectId;
            this.score = score;
            this.searchIndexes = searchIndexes;
        }
    }

    private record SearchRequestKey(String query, Set<SourceContentType> contentTypes,
                                    Integer pageStart, Integer pageEnd, int maxResults) {
        private static SearchRequestKey from(PaperSearchRequest request) {
            return new SearchRequestKey(request.query().toLowerCase(Locale.ROOT), request.contentTypes(),
                    request.pageStart(), request.pageEnd(), request.maxResults());
        }
    }

    private record FormulaExpansion(boolean expanded) { }

    private record PageHint(Integer start, Integer end) { }

}
