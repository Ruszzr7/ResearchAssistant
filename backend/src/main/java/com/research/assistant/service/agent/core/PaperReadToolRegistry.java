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
import com.research.assistant.service.agent.source.SourceObject;
import com.research.assistant.service.memory.PaperGlobalProfile;
import com.research.assistant.service.memory.PaperMemoryClaim;
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
    static final int MAX_SEARCH_RESULTS = 8;
    static final int MAX_PAGE_SPAN = 2;
    // Keep each read compact enough for the next model turn. The Agent may request
    // another read when it needs a different or missing part of the paper.
    static final int MAX_SOURCES_PER_RESULT = 6;
    static final int MAX_TARGETED_SOURCES_PER_RESULT = 8;
    static final int MAX_CONTENT_CHARACTERS = 8_000;
    static final int MAX_RESULT_BYTES = 16 * 1024;
    private static final Pattern SUBFORMULA_NUMBER = Pattern.compile("^(\\d{1,4})([a-z])$",
            Pattern.CASE_INSENSITIVE);
    private static final String EVIDENCE_SCHEMA = """
            {"type":"object","properties":{
            "needs":{"type":"array","maxItems":4,"items":{"type":"object","properties":{
            "id":{"type":"string","maxLength":40},"query":{"type":"string","maxLength":400,"description":"Search text for this need. Provide query or sourceObjectIds."},
            "keywords":{"type":"array","maxItems":8,"items":{"type":"string","maxLength":120}},
            "targets":{"type":"array","maxItems":8,"items":{"type":"string","maxLength":120}},
            "sectionHint":{"type":"string","maxLength":120},
            "pageHints":{"type":"array","maxItems":2,"items":{"type":"integer","minimum":1}},
            "profileClaimRefs":{"type":"array","maxItems":4,"items":{"type":"string","maxLength":40}},
            "contentTypes":{"type":"array","items":{"type":"string","enum":["TEXT","FORMULA","TABLE","FIGURE","ALGORITHM"]}},
            "sourceObjectIds":{"type":"array","maxItems":4,"items":{"type":"string","maxLength":160},"description":"Known trusted source IDs to read directly without searching again. Provide sourceObjectIds or query."},
            "includeVisual":{"type":"boolean","description":"Attach a source-linked crop when visual inspection is needed."}},
            "additionalProperties":false}},
            "pageRanges":{"type":"array","maxItems":2,"items":{"type":"object","properties":{"startPage":{"type":"integer","minimum":1},"endPage":{"type":"integer","minimum":1}},"required":["startPage","endPage"],"additionalProperties":false}},
            "maxEvidence":{"type":"integer","minimum":1,"maximum":8}},"additionalProperties":false}
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
                "Retrieve citable page-level evidence from the current paper before composing paper-dependent factual answers. Put all currently known evidence needs in one needs array and use targets for explicit formulas, figures, metrics, sections, methods, or other items that must all be covered. Set includeVisual only when the formula, figure, table, or algorithm must be inspected as an image; up to two source-linked crops are returned to the same Agent. Known sourceObjectIds can be read directly without searching again. One call performs focused retrieval with at most one fallback per need, expands structurally related formula parts, and reports per-need coverage. After receiving sources, judge their sufficiency and answer; call again only for a specific unresolved fact with a genuinely different query. If no new source is returned or exhausted is true, stop searching and state the limitation instead of inventing evidence.", EVIDENCE_SCHEMA));
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
                default -> throw new IllegalArgumentException("unknown read-only tool: " + name);
            };
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException("invalid arguments for tool " + name, error);
        }
    }

    private AgentToolExecution retrieveEvidence(PaperSourceCatalog catalog, JsonNode args,
                                                 String evidenceFocus) throws Exception {
        JsonNode searches = args.path("needs");
        if (!searches.isArray() || searches.isEmpty()) searches = args.path("searches");
        JsonNode requestedNeeds = searches;
        JsonNode pageRanges = args.path("pageRanges");
        boolean hasSearches = requestedNeeds.isArray() && !requestedNeeds.isEmpty();
        boolean hasPages = pageRanges.isArray() && !pageRanges.isEmpty();
        if (!hasSearches && !hasPages) {
            throw new IllegalArgumentException("at least one search or page range is required");
        }

        Map<String, MergedHit> merged = new LinkedHashMap<>();
        Map<Integer, List<MergedHit>> hitsBySearch = new LinkedHashMap<>();
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
                if (!catalog.objects().containsKey(sourceId)) continue;
                MergedHit direct = merged.computeIfAbsent(sourceId,
                        id -> new MergedHit(id, 1.10, new LinkedHashSet<>()));
                direct.score = Math.max(direct.score, 1.10);
                direct.searchIndexes.add(index);
                searchHits.put(sourceId, direct);
            }
            if (query.isEmpty()) {
                hitsBySearch.put(index, new ArrayList<>(searchHits.values()));
                continue;
            }
            PageHint pageHint = pageHint(search);
            PaperSearchRequest request = new PaperSearchRequest(query, types, pageHint.start(), pageHint.end(),
                    MAX_SEARCH_RESULTS);
            int refCount = 0;
            for (JsonNode ref : search.path("profileClaimRefs")) {
                if (refCount++ >= 4) break;
                for (String sourceId : claimIndex.sources().getOrDefault(ref.asText(""), Set.of())) {
                    MergedHit claimHit = merged.computeIfAbsent(sourceId,
                            id -> new MergedHit(id, 1.05, new LinkedHashSet<>()));
                    claimHit.score = Math.max(claimHit.score, 1.05);
                    claimHit.searchIndexes.add(index);
                    searchHits.put(sourceId, claimHit);
                }
            }
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
            mergeRetrievedHits(sourceService.search(catalog, request), index, merged, searchHits);
            if (searchHits.isEmpty()) {
                String fallbackQuery = fallbackQuery(search, query);
                Set<SourceContentType> fallbackTypes = types;
                if (fallbackQuery.equals(query) && !types.isEmpty()) fallbackTypes = Set.of();
                if (!fallbackQuery.isBlank()
                        && (!fallbackQuery.equals(query) || !fallbackTypes.equals(types))) {
                    PaperSearchRequest fallbackRequest = new PaperSearchRequest(fallbackQuery, fallbackTypes,
                            pageHint.start(), pageHint.end(), MAX_SEARCH_RESULTS);
                    mergeRetrievedHits(sourceService.search(catalog, fallbackRequest), index, merged, searchHits);
                }
            }
            hitsBySearch.put(index, new ArrayList<>(searchHits.values()));
        }

        FormulaExpansion formulaExpansion = expandFormulaFamilies(catalog, merged, hitsBySearch);
        boolean hasExplicitTargets = hasSearches && hasTargets(requestedNeeds, searchCount);
        int sourceLimit = hasExplicitTargets || formulaExpansion.expanded()
                ? MAX_TARGETED_SOURCES_PER_RESULT : MAX_SOURCES_PER_RESULT;
        int requested = Math.max(1, Math.min(sourceLimit,
                args.path("maxEvidence").asInt(MAX_SOURCES_PER_RESULT)));
        requested = Math.min(sourceLimit, Math.max(requested, searchCount));
        if (formulaExpansion.expanded()) requested = sourceLimit;
        LinkedHashMap<String, MergedHit> selectedById = new LinkedHashMap<>();
        if (hasPages) {
            for (int rangeIndex = 0; rangeIndex < Math.min(pageRanges.size(), 2); rangeIndex++) {
                JsonNode range = pageRanges.get(rangeIndex);
                int startPage = range.path("startPage").asInt();
                int endPage = range.path("endPage").asInt();
                if (startPage <= 0 || endPage < startPage || endPage - startPage + 1 > MAX_PAGE_SPAN) {
                    continue;
                }
                for (SourceObject source : sourceService.readPages(
                        catalog, startPage, endPage, MAX_CONTENT_CHARACTERS)) {
                    MergedHit hit = merged.computeIfAbsent(source.sourceObjectId(),
                            id -> new MergedHit(id, 0.85, new LinkedHashSet<>()));
                    selectedById.putIfAbsent(source.sourceObjectId(), hit);
                    if (selectedById.size() >= requested) break;
                }
                if (selectedById.size() >= requested) break;
            }
        }
        // Reserve the first available result for each independent evidence need before filling
        // the remaining slots globally. This prevents a high-scoring query from starving all
        // other searches in one batch response.
        for (List<MergedHit> searchHits : hitsBySearch.values()) {
            if (selectedById.size() >= requested) break;
            searchHits.stream()
                    .sorted(Comparator.comparingDouble((MergedHit hit) -> hit.score).reversed()
                            .thenComparing(hit -> hit.sourceObjectId))
                    .findFirst()
                    .ifPresent(hit -> selectedById.putIfAbsent(hit.sourceObjectId, hit));
        }
        merged.values().stream()
                .sorted(Comparator.comparingDouble((MergedHit hit) -> hit.score).reversed()
                        .thenComparing(hit -> hit.sourceObjectId))
                .filter(hit -> !selectedById.containsKey(hit.sourceObjectId))
                .limit(Math.max(0, requested - selectedById.size()))
                .forEach(hit -> selectedById.putIfAbsent(hit.sourceObjectId, hit));

        List<MergedHit> selected = List.copyOf(selectedById.values());
        List<SourceObject> sources = new ArrayList<>();
        for (MergedHit hit : selected) sources.add(sourceService.readSource(catalog, hit.sourceObjectId));
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
                        hitsBySearch, selectedById);
                long foundNeeds = evidenceNeeds.stream()
                        .filter(need -> !"not_found".equals(need.get("status"))).count();
                boolean hasUnresolvedTargets = evidenceNeeds.stream().anyMatch(need -> {
                    Object unresolved = need.get("unresolvedTargets");
                    return unresolved instanceof List<?> values && !values.isEmpty();
                });
                String coverage = searchCount == 0 ? (compact.isEmpty() ? "not_found" : "found")
                        : foundNeeds == 0 ? "not_found" : hasUnresolvedTargets ? "partial"
                        : hasExplicitTargets && foundNeeds == searchCount ? "complete" : "found";
                List<String> unresolvedTargets = evidenceNeeds.stream().flatMap(need -> {
                    Object value = need.get("unresolvedTargets");
                    return value instanceof List<?> values
                            ? values.stream().map(String.class::cast) : java.util.stream.Stream.empty();
                }).distinct().toList();
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("status", status);
                payload.put("coverage", coverage);
                payload.put("untrustedPaperContent", true);
                payload.put("sources", compact);
                payload.put("evidenceNeeds", evidenceNeeds);
                payload.put("matchedProfileClaimRefs", List.copyOf(matchedProfileClaimRefs));
                payload.put("unresolvedTargets", unresolvedTargets);
                payload.put("exhausted", compact.isEmpty());
                payload.put("requestedSearches", searchCount);
                payload.put("requestedNeeds", searchCount);
                payload.put("requestedPageRanges", hasPages ? Math.min(pageRanges.size(), 2) : 0);
                payload.put("usage", compact.isEmpty()
                        ? "No supporting source was found. Stop searching this need, use other valid context, or state that the paper does not provide the evidence."
                        : "Judge whether the returned original sources support the answer. Search again only for a specific missing fact represented by unresolvedTargets and use a genuinely different query. If newSourceCount is 0 or exhausted is true, stop.");
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

    private List<Map<String, Object>> buildEvidenceNeeds(PaperSourceCatalog catalog,
                                                          JsonNode requestedNeeds,
                                                          Map<Integer, List<MergedHit>> hitsBySearch,
                                                          Map<String, MergedHit> selectedById) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<Integer, List<MergedHit>> entry : hitsBySearch.entrySet()) {
            JsonNode need = requestedNeeds.get(entry.getKey());
            String id = needId(need, entry.getKey());
            List<String> returnedIds = entry.getValue().stream().map(hit -> hit.sourceObjectId)
                    .filter(selectedById::containsKey).toList();
            List<String> targets = targets(need);
            List<String> coveredTargets = targets.stream().filter(target -> returnedIds.stream()
                    .map(catalog::requireObject).anyMatch(source -> targetMatches(source, target))).toList();
            List<String> unresolvedTargets = targets.stream()
                    .filter(target -> !coveredTargets.contains(target)).toList();
            String needStatus = returnedIds.isEmpty() ? "not_found"
                    : unresolvedTargets.isEmpty() ? "found" : "partial";
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("needId", id);
            value.put("searchIndex", entry.getKey());
            value.put("status", needStatus);
            value.put("sourceObjectIds", returnedIds);
            value.put("targets", targets);
            value.put("coveredTargets", coveredTargets);
            value.put("unresolvedTargets", unresolvedTargets);
            value.put("missing", returnedIds.isEmpty()
                    ? List.of("no matching source returned") : unresolvedTargets);
            result.add(value);
        }
        return result;
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
        String formulaNumber = SourceObject.normalize(source.formulaNumber()).toLowerCase(Locale.ROOT);
        String formulaTarget = normalizedTarget.replaceAll("^[\\(\\[]|[\\)\\]]$", "");
        if (!formulaNumber.isBlank() && formulaNumber.equals(formulaTarget)) return true;
        String searchable = (source.normalizedContent() + " "
                + String.join(" ", source.sectionPath()) + " " + source.formulaNumber())
                .toLowerCase(Locale.ROOT);
        if (searchable.contains(normalizedTarget)) return true;
        return !formulaTarget.equals(normalizedTarget) && searchable.contains(formulaTarget);
    }

    private ProfileClaimIndex profileClaimIndex(PaperSourceCatalog catalog) {
        if (memoryMapper == null || catalog == null) return ProfileClaimIndex.EMPTY;
        PaperMemoryRecord memory = memoryMapper.selectLatest(catalog.paperId());
        if (memory == null || memory.getProfileJson() == null
                || !catalog.documentHash().equals(memory.getDocumentHash())
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
            throw new IllegalArgumentException("read_pages accepts at most two consecutive pages");
        }
        List<SourceObject> sources = sourceService.readPages(catalog, startPage,
                endPage, Math.min(MAX_CONTENT_CHARACTERS,
                        Math.max(1_000, args.path("maxCharacters").asInt(MAX_CONTENT_CHARACTERS))));
        return sourcesResult(catalog, sources);
    }

    private AgentToolExecution sourcesResult(PaperSourceCatalog catalog, List<SourceObject> sources) throws Exception {
        List<SourceObject> bounded = sources.stream().limit(MAX_SOURCES_PER_RESULT).toList();
        Set<String> ids = bounded.stream().map(SourceObject::sourceObjectId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        int perSource = Math.max(240, MAX_CONTENT_CHARACTERS / Math.max(1, bounded.size()));
        List<Map<String, Object>> compact = bounded.stream()
                .map(source -> compactSource(catalog, source, perSource)).toList();
        return result(Map.of("untrustedPaperContent", true, "sources", compact,
                "truncated", sources.size() > bounded.size()), ids);
    }

    private AgentToolExecution result(Object value, Set<String> ids) throws Exception {
        String json = objectMapper.writeValueAsString(value);
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_RESULT_BYTES) {
            throw new IllegalStateException("tool result exceeded the model payload limit");
        }
        return new AgentToolExecution(json, ids);
    }

    private Map<String, Object> compactSource(PaperSourceCatalog catalog, SourceObject source, int maxCharacters) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("sourceObjectId", source.sourceObjectId());
        value.put("contentType", source.contentType());
        Object sourceRole = source.provenance().get("role");
        if (sourceRole != null && !sourceRole.toString().isBlank()) value.put("sourceRole", sourceRole);
        if (catalog != null) {
            value.put("page", catalog.requireLocators(source.sourceObjectId()).get(0).pageNumber());
        }
        value.put("sectionPath", source.sectionPath());
        if (!source.formulaNumber().isBlank()) value.put("formulaNumber", source.formulaNumber());
        value.put("content", truncate(source.rawContent(), maxCharacters));
        value.put("truncated", source.rawContent().length() > maxCharacters);
        return value;
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

    private record FormulaExpansion(boolean expanded) { }

    private record PageHint(Integer start, Integer end) { }

}
