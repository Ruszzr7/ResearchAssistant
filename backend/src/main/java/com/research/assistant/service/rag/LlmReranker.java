package com.research.assistant.service.rag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.common.JsonUtils;
import com.research.assistant.service.LLMService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 基于 LLM 的 RAG 片段重排序器。
 * <p>
 * 对候选片段一次性打分（1–10），按相关性返回 top-K。
 * LLM 调用失败时原样返回输入列表的前 topK 个，避免阻断主流程。
 */
@Service
public class LlmReranker {

    private static final Logger log = LoggerFactory.getLogger(LlmReranker.class);

    private final LLMService llmService;
    private final ObjectMapper objectMapper;

    public LlmReranker(LLMService llmService, ObjectMapper objectMapper) {
        this.llmService = llmService;
        this.objectMapper = objectMapper;
    }

    /**
     * 对候选片段重排序。
     *
     * @param query      用户查询
     * @param candidates 候选片段（已按向量相似度粗排）
     * @param topK       最终返回数量
     * @return 按 LLM 相关性分数重排后的片段；失败时返回 candidates 的前 topK 个
     */
    public List<ScoredChunk> rerank(String query, List<ScoredChunk> candidates, int topK) {
        if (candidates == null || candidates.isEmpty()) {
            return candidates;
        }
        int limit = Math.max(1, topK);
        try {
            String prompt = buildPrompt(query, candidates);
            String system = """
                    You are a relevance scoring assistant.
                    Score how relevant each candidate snippet is to the user's query on a scale of 1 (not relevant) to 10 (highly relevant).
                    Return ONLY a JSON array in the format:
                    [{"index":1,"score":8,"reason":"brief reason"}, ...]
                    Do not wrap the JSON in markdown code blocks. Do not add any explanation outside the JSON.
                    """;
            String raw = llmService.chat(system, prompt);
            String json = JsonUtils.extractJson(raw);
            if (json == null || json.isBlank()) {
                return firstN(candidates, limit);
            }
            List<RankScore> scores = objectMapper.readValue(json, new TypeReference<>() {
            });
            return applyScores(candidates, scores, limit);
        } catch (Exception e) {
            log.warn("LLM rerank failed, falling back to vector ranking: {}", e.getMessage());
            return firstN(candidates, limit);
        }
    }

    private String buildPrompt(String query, List<ScoredChunk> candidates) {
        StringBuilder sb = new StringBuilder();
        sb.append("Query: ").append(query).append("\n\n");
        sb.append("Candidate snippets (numbered by index):\n");
        for (int i = 0; i < candidates.size(); i++) {
            sb.append(i + 1).append(". ")
                    .append(truncate(candidates.get(i).content(), 600))
                    .append("\n");
        }
        sb.append("\nScore each candidate's relevance to the query.");
        return sb.toString();
    }

    private List<ScoredChunk> applyScores(List<ScoredChunk> candidates, List<RankScore> scores, int limit) {
        if (scores == null || scores.isEmpty()) {
            return firstN(candidates, limit);
        }
        List<RankScore> validScores = new ArrayList<>();
        for (RankScore rs : scores) {
            int idx = rs.resolveIndex();
            if (idx >= 1 && idx <= candidates.size()) {
                validScores.add(rs);
            }
        }
        if (validScores.isEmpty()) {
            return firstN(candidates, limit);
        }
        validScores.sort(Comparator.comparingDouble(RankScore::score).reversed());
        List<ScoredChunk> result = new ArrayList<>(limit);
        for (int i = 0; i < Math.min(limit, validScores.size()); i++) {
            RankScore rs = validScores.get(i);
            ScoredChunk original = candidates.get(rs.resolveIndex() - 1);
            result.add(original);
        }
        return result;
    }

    private List<ScoredChunk> firstN(List<ScoredChunk> candidates, int n) {
        return candidates.stream().limit(n).toList();
    }

    private String truncate(String text, int max) {
        if (text == null || text.length() <= max) {
            return text;
        }
        return text.substring(0, max) + "…";
    }

    private record RankScore(Integer index, Integer id, Double score, String reason) {
        int resolveIndex() {
            if (index != null && index > 0) {
                return index;
            }
            if (id != null && id > 0) {
                return id;
            }
            return -1;
        }
    }
}
