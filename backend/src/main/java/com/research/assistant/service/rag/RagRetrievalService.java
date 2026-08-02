package com.research.assistant.service.rag;

import com.research.assistant.entity.PaperChunk;
import com.research.assistant.mapper.PaperChunkMapper;
import com.research.assistant.service.SettingsService;
import com.research.assistant.service.observability.ResearchMetrics;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Local lexical retrieval for legacy skills. The PDF workbench uses layout evidence directly. */
@Service
public class RagRetrievalService {

    private static final String RAG_ENABLED_KEY = "rag_enabled";
    private static final Pattern TERM = Pattern.compile("[\\p{IsLatin}\\p{IsGreek}\\p{N}_+/-]{2,}|[\\p{IsHan}]{2,}");

    private final PaperChunkMapper chunkMapper;
    private final SettingsService settingsService;
    private final ResearchMetrics metrics;

    public RagRetrievalService(PaperChunkMapper chunkMapper,
                               SettingsService settingsService,
                               ResearchMetrics metrics) {
        this.chunkMapper = chunkMapper;
        this.settingsService = settingsService;
        this.metrics = metrics;
    }

    public List<ScoredChunk> retrieve(String query, int maxResults, double minScore) {
        return retrieveWithStatus(query, maxResults, minScore).chunks();
    }

    public RagRetrievalResult retrieveWithStatus(String query, int maxResults, double minScore) {
        long startedAt = metrics.startTimer();
        if (!isEnabled() || query == null || query.isBlank()) {
            return record(RagRetrievalResult.empty(RagRetrievalStatus.DISABLED), startedAt);
        }
        int safeMax = Math.max(1, Math.min(100, maxResults));
        double safeMin = Math.max(0, Math.min(1, minScore));
        List<String> terms = terms(query);
        List<ScoredChunk> result = chunkMapper.selectAllActive().stream()
                .map(record -> scored(record, query, terms))
                .filter(item -> item.score() >= safeMin)
                .sorted(Comparator.comparingDouble(ScoredChunk::score).reversed()
                        .thenComparing(ScoredChunk::paperId)
                        .thenComparing(item -> item.pageStart() == null ? Integer.MAX_VALUE : item.pageStart()))
                .limit(safeMax)
                .toList();
        RagRetrievalStatus status = result.isEmpty() ? RagRetrievalStatus.EMPTY : RagRetrievalStatus.SUCCESS;
        Integer activeVersion = result.stream().map(ScoredChunk::indexVersion)
                .filter(java.util.Objects::nonNull).findFirst().orElse(null);
        return record(new RagRetrievalResult(status, result, activeVersion, result.size()), startedAt);
    }

    /** Kept for callers; local deterministic ranking is already final and makes no extra LLM call. */
    public List<ScoredChunk> retrieveAndRerank(String query, int retrieveK, double minScore) {
        return retrieve(query, retrieveK, minScore);
    }

    public String retrieveAsContext(String query, int maxResults, double minScore) {
        return formatAsContext(retrieve(query, maxResults, minScore));
    }

    public String retrieveAndRerankAsContext(String query, int retrieveK, double minScore) {
        return formatAsContext(retrieve(query, retrieveK, minScore));
    }

    private ScoredChunk scored(PaperChunk record, String query, List<String> terms) {
        String content = normalize(record.getContent());
        String normalizedQuery = normalize(query);
        long matched = terms.stream().filter(content::contains).count();
        double coverage = terms.isEmpty() ? 0 : matched / (double) terms.size();
        double phrase = !normalizedQuery.isBlank() && content.contains(normalizedQuery) ? 0.25 : 0;
        double type = "RAW".equalsIgnoreCase(record.getChunkType()) ? 0.05 : 0.10;
        double score = Math.min(1, 0.65 * coverage + phrase + type);
        return new ScoredChunk(record.getPaperId(), record.getChunkType(), record.getContent(),
                record.getSource(), score, record.getChunkKey(), record.getIndexVersion(),
                record.getSourceType(), record.getPageStart(), record.getPageEnd(),
                record.getCharStart(), record.getCharEnd(), null);
    }

    private List<String> terms(String query) {
        Set<String> result = new LinkedHashSet<>();
        Matcher matcher = TERM.matcher(normalize(query));
        while (matcher.find()) result.add(matcher.group());
        return List.copyOf(result);
    }

    private String formatAsContext(List<ScoredChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) return "";
        StringBuilder value = new StringBuilder("\n\n以下是本地索引命中的论文片段：\n");
        for (int index = 0; index < chunks.size(); index++) {
            ScoredChunk chunk = chunks.get(index);
            value.append(index + 1).append(". [evidenceId=").append(chunk.evidenceId())
                    .append(", paperId=").append(chunk.paperId()).append("]\n")
                    .append(chunk.content()).append('\n');
        }
        return value.toString();
    }

    private String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private boolean isEnabled() {
        String value = settingsService.getValue(RAG_ENABLED_KEY);
        return value == null || Boolean.parseBoolean(value);
    }

    private RagRetrievalResult record(RagRetrievalResult result, long startedAt) {
        metrics.ragRetrievalFinished(result.status().name().toLowerCase(Locale.ROOT),
                result.chunks().size(), startedAt);
        return result;
    }
}
