package com.research.assistant.service.source;

import com.research.assistant.entity.Paper;
import com.research.assistant.mapper.PaperMapper;
import com.research.assistant.service.SemanticScholarFetcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 引用网络扩展服务 —— 基于 Semantic Scholar paperId 进行前向/后向引用与作者追踪。
 */
@Service
public class CitationNetworkExpansionService {

    private static final Logger log = LoggerFactory.getLogger(CitationNetworkExpansionService.class);

    private static final String DIRECTION_FORWARD = "forward";
    private static final String DIRECTION_BACKWARD = "backward";
    private static final String DIRECTION_AUTHOR = "author";

    private final SemanticScholarFetcher fetcher;
    private final PaperMapper paperMapper;
    private final LiteratureSearchService literatureSearchService;

    public CitationNetworkExpansionService(SemanticScholarFetcher fetcher,
                                           PaperMapper paperMapper,
                                           LiteratureSearchService literatureSearchService) {
        this.fetcher = fetcher;
        this.paperMapper = paperMapper;
        this.literatureSearchService = literatureSearchService;
    }

    /**
     * 按本地论文 ID 扩展。
     * 若本地论文没有保存 semanticScholarId，则返回空列表。
     */
    public List<LiteratureCandidate> expandByLocalPaperId(Long localPaperId,
                                                         List<String> directions,
                                                         int limit) {
        if (localPaperId == null) {
            return List.of();
        }
        Paper paper = paperMapper.selectById(localPaperId);
        if (paper == null || paper.getSemanticScholarId() == null || paper.getSemanticScholarId().isBlank()) {
            log.debug("本地论文 {} 没有 Semantic Scholar ID，无法扩展引用网络", localPaperId);
            return List.of();
        }
        return expandByS2Id(paper.getSemanticScholarId(), directions, limit);
    }

    /**
     * 按原始 Semantic Scholar paperId 扩展。
     */
    public List<LiteratureCandidate> expandByS2Id(String s2PaperId,
                                                  List<String> directions,
                                                  int limit) {
        if (s2PaperId == null || s2PaperId.isBlank()) {
            return List.of();
        }
        List<String> dirs = (directions == null || directions.isEmpty())
                ? List.of(DIRECTION_FORWARD, DIRECTION_BACKWARD, DIRECTION_AUTHOR)
                : directions.stream().map(String::toLowerCase).distinct().toList();

        List<LiteratureCandidate> all = new ArrayList<>();
        int perDirLimit = Math.max(1, limit / Math.max(1, dirs.size()));

        for (String dir : dirs) {
            try {
                switch (dir) {
                    case DIRECTION_FORWARD -> all.addAll(fetchForward(s2PaperId, perDirLimit));
                    case DIRECTION_BACKWARD -> all.addAll(fetchBackward(s2PaperId, perDirLimit));
                    case DIRECTION_AUTHOR -> all.addAll(fetchAuthorPapers(s2PaperId, perDirLimit));
                    default -> log.warn("未知的引用网络扩展方向: {}", dir);
                }
            } catch (Exception e) {
                log.warn("引用网络扩展方向 {} 失败: {}", dir, e.getMessage());
            }
        }

        return literatureSearchService.deduplicate(all);
    }

    private List<LiteratureCandidate> fetchForward(String s2PaperId, int limit) {
        List<Map<String, Object>> papers = fetcher.fetchCitations(s2PaperId, limit);
        return papers.stream().map(SemanticScholarSource::toCandidate).toList();
    }

    private List<LiteratureCandidate> fetchBackward(String s2PaperId, int limit) {
        List<Map<String, Object>> papers = fetcher.fetchReferences(s2PaperId, limit);
        return papers.stream().map(SemanticScholarSource::toCandidate).toList();
    }

    private List<LiteratureCandidate> fetchAuthorPapers(String s2PaperId, int limit) {
        List<String> authorIds = fetcher.fetchPaperAuthorIds(s2PaperId);
        if (authorIds.isEmpty()) {
            return List.of();
        }
        // 取前 3 位作者，limit 在作者间均分
        int maxAuthors = Math.min(3, authorIds.size());
        int limitPerAuthor = Math.max(1, limit / maxAuthors);
        List<LiteratureCandidate> result = new ArrayList<>();
        for (int i = 0; i < maxAuthors; i++) {
            List<Map<String, Object>> papers = fetcher.fetchAuthorPapers(authorIds.get(i), limitPerAuthor);
            result.addAll(papers.stream().map(SemanticScholarSource::toCandidate).toList());
        }
        return result;
    }
}
