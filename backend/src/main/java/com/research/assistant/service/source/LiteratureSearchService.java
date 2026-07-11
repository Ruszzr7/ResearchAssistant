package com.research.assistant.service.source;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskExecutor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 多源文献检索编排服务。
 * <p>
 * 自动收集所有 {@link LiteratureSource} Bean，按来源并行搜索、统一去重、排序并生成推荐理由。
 */
@Service
public class LiteratureSearchService {

    private static final Logger log = LoggerFactory.getLogger(LiteratureSearchService.class);

    private final List<LiteratureSource> sources;
    private final TaskExecutor taskExecutor;

    public LiteratureSearchService(List<LiteratureSource> sources,
                                   @Qualifier("literatureSearchExecutor") TaskExecutor taskExecutor) {
        this.sources = sources != null ? sources : List.of();
        this.taskExecutor = taskExecutor;
    }

    /**
     * 使用所有可用来源并行检索。
     *
     * @param keywords     英文关键词列表
     * @param maxPerSource 每来源每关键词最大返回数
     * @return 去重排序后的候选列表
     */
    public List<LiteratureCandidate> search(List<String> keywords, int maxPerSource) {
        if (sources.isEmpty()) {
            log.warn("没有可用的 LiteratureSource");
            return List.of();
        }
        if (keywords == null || keywords.isEmpty()) {
            return List.of();
        }

        List<CompletableFuture<List<LiteratureCandidate>>> futures = new ArrayList<>();
        for (LiteratureSource source : sources) {
            if (!source.supportsSearch()) continue;
            CompletableFuture<List<LiteratureCandidate>> future = CompletableFuture
                    .supplyAsync(() -> source.searchKeywords(keywords, maxPerSource), taskExecutor)
                    .orTimeout(30, TimeUnit.SECONDS)
                    .exceptionally(e -> {
                        log.warn("来源 {} 检索失败: {}", source.sourceName(), e.getMessage());
                        return List.of();
                    });
            futures.add(future);
        }

        List<LiteratureCandidate> all = new ArrayList<>();
        for (CompletableFuture<List<LiteratureCandidate>> future : futures) {
            try {
                all.addAll(future.get());
            } catch (Exception e) {
                log.warn("等待来源结果失败: {}", e.getMessage());
            }
        }

        return deduplicateAndSort(all);
    }

    /**
     * 对候选列表进行去重排序（供外部手动合并多个查询结果）。
     */
    public List<LiteratureCandidate> deduplicate(List<LiteratureCandidate> candidates) {
        return deduplicateAndSort(candidates);
    }

    /**
     * 将候选转换为前端/Skill 需要的 Map，并附加基于关键词的推荐理由。
     */
    public List<Map<String, Object>> toResultMaps(List<LiteratureCandidate> candidates, List<String> keywords) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (LiteratureCandidate c : candidates) {
            Map<String, Object> map = c.toMap();
            map.put("recommendReason", generateReason(c, keywords));
            result.add(map);
        }
        return result;
    }

    private List<LiteratureCandidate> deduplicateAndSort(List<LiteratureCandidate> candidates) {
        Map<String, LiteratureCandidate> seen = new LinkedHashMap<>();
        for (LiteratureCandidate c : candidates) {
            String key = c.dedupeKey();
            LiteratureCandidate existing = seen.get(key);
            if (existing == null) {
                seen.put(key, c);
            } else {
                seen.put(key, merge(existing, c));
            }
        }

        List<LiteratureCandidate> list = new ArrayList<>(seen.values());
        list.sort(Comparator
                .comparing(LiteratureCandidate::hasExternalId).reversed()
                .thenComparing((LiteratureCandidate c) -> c.summary() != null && !c.summary().isBlank()).reversed()
                .thenComparing(this::parseYear, Comparator.reverseOrder())
                .thenComparing(c -> c.source() != null ? c.source().split(",\\s*").length : 0, Comparator.reverseOrder()));
        return list;
    }

    private LiteratureCandidate merge(LiteratureCandidate a, LiteratureCandidate b) {
        return new LiteratureCandidate(
                a.title(),
                a.authors().isBlank() ? b.authors() : a.authors(),
                a.year().isBlank() ? b.year() : a.year(),
                a.summary().isBlank() ? b.summary() : a.summary(),
                a.arxivId().isBlank() ? b.arxivId() : a.arxivId(),
                a.doi().isBlank() ? b.doi() : a.doi(),
                a.sourceUrl().isBlank() ? b.sourceUrl() : a.sourceUrl(),
                a.pdfUrl().isBlank() ? b.pdfUrl() : a.pdfUrl(),
                LiteratureCandidate.mergeSources(a.source(), b.source()),
                a.externalId().isBlank() ? b.externalId() : a.externalId()
        );
    }

    private int parseYear(LiteratureCandidate c) {
        if (c.year() == null || c.year().isBlank()) return 0;
        try {
            return Integer.parseInt(c.year().trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String generateReason(LiteratureCandidate c, List<String> keywords) {
        if (keywords == null || keywords.isEmpty()) {
            return "来自 " + c.source();
        }
        String titleLower = String.valueOf(c.title()).toLowerCase();
        String summaryLower = String.valueOf(c.summary()).toLowerCase();
        List<String> matched = new ArrayList<>();
        for (String kw : keywords) {
            if (kw == null) continue;
            String k = kw.trim().toLowerCase();
            if (!k.isBlank() && (titleLower.contains(k) || summaryLower.contains(k))) {
                matched.add(kw.trim());
            }
        }
        if (matched.isEmpty()) {
            return "来自 " + c.source();
        }
        return "匹配关键词：" + String.join("、", matched) + "（" + c.source() + "）";
    }
}
