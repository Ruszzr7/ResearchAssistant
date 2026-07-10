package com.research.assistant.service.rag;

import com.research.assistant.service.SettingsService;
import com.research.assistant.service.embedding.EmbeddingService;
import com.research.assistant.service.embedding.EmbeddingUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/**
 * RAG 召回服务 —— 将用户查询转换为 embedding 并检索相关论文片段。
 */
@Service
public class RagRetrievalService {

    private static final Logger log = LoggerFactory.getLogger(RagRetrievalService.class);

    private static final String RAG_ENABLED_KEY = "rag_enabled";
    private static final String RAG_RERANK_ENABLED_KEY = "rag_rerank_enabled";
    private static final String RAG_RERANK_TOP_K_KEY = "rag_rerank_top_k";
    private static final String RAG_ANSWER_TOP_K_KEY = "rag_answer_top_k";
    private static final String RAG_RERANK_MIN_CHUNKS_KEY = "rag_rerank_min_chunks";

    private final EmbeddingService embeddingService;
    private final VectorStore vectorStore;
    private final SettingsService settingsService;
    private final LlmReranker llmReranker;

    public RagRetrievalService(EmbeddingService embeddingService,
                               VectorStore vectorStore,
                               SettingsService settingsService,
                               LlmReranker llmReranker) {
        this.embeddingService = embeddingService;
        this.vectorStore = vectorStore;
        this.settingsService = settingsService;
        this.llmReranker = llmReranker;
    }

    /**
     * 检索与查询相关的论文片段。
     *
     * @param query      用户查询
     * @param maxResults 最大返回数
     * @param minScore   最低相似度阈值
     * @return 相关片段列表；RAG 未启用或 embedding 失败时返回空列表
     */
    public List<ScoredChunk> retrieve(String query, int maxResults, double minScore) {
        if (!isEnabled() || query == null || query.isBlank()) {
            return Collections.emptyList();
        }
        try {
            List<Float> embedding = embeddingService.embed(query);
            return vectorStore.findRelevant(embedding, maxResults, minScore);
        } catch (EmbeddingUnavailableException e) {
            log.warn("RAG 召回失败，将降级: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 检索并格式化为 LLM 上下文字符串。
     */
    public String retrieveAsContext(String query, int maxResults, double minScore) {
        List<ScoredChunk> chunks = retrieve(query, maxResults, minScore);
        return formatAsContext(chunks);
    }

    /**
     * 召回 + LLM 重排序，返回 top-K 片段。
     *
     * @param query     用户查询
     * @param retrieveK 向量召回数量
     * @param minScore  最低相似度阈值
     * @return 经 LLM 重排序后的片段；重排序关闭或失败时回退到向量排序
     */
    public List<ScoredChunk> retrieveAndRerank(String query, int retrieveK, double minScore) {
        List<ScoredChunk> retrieved = retrieve(query, retrieveK, minScore);
        if (!isRerankEnabled()) {
            return retrieved;
        }
        int minChunks = getRerankMinChunks();
        if (retrieved == null || retrieved.size() < minChunks) {
            return retrieved;
        }
        int rerankTopK = getRerankTopK();
        int answerTopK = getAnswerTopK();
        List<ScoredChunk> toRerank = retrieved.size() > rerankTopK ? retrieved.subList(0, rerankTopK) : retrieved;
        return llmReranker.rerank(query, toRerank, answerTopK);
    }

    /**
     * 召回 + LLM 重排序，并格式化为 LLM 上下文字符串。
     */
    public String retrieveAndRerankAsContext(String query, int retrieveK, double minScore) {
        List<ScoredChunk> chunks = retrieveAndRerank(query, retrieveK, minScore);
        return formatAsContext(chunks);
    }

    private String formatAsContext(List<ScoredChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n\n以下是与问题相关的论文片段（按相关度排序）：\n");
        for (int i = 0; i < chunks.size(); i++) {
            ScoredChunk c = chunks.get(i);
            sb.append(i + 1).append(". [paperId=").append(c.paperId())
                    .append(", source=").append(c.source()).append("]\n")
                    .append(c.content()).append("\n");
        }
        return sb.toString();
    }

    private boolean isRerankEnabled() {
        return Boolean.parseBoolean(getSetting(RAG_RERANK_ENABLED_KEY, "false"));
    }

    private int getRerankTopK() {
        return parseInt(getSetting(RAG_RERANK_TOP_K_KEY, "10"), 10);
    }

    private int getAnswerTopK() {
        return parseInt(getSetting(RAG_ANSWER_TOP_K_KEY, "5"), 5);
    }

    private int getRerankMinChunks() {
        return parseInt(getSetting(RAG_RERANK_MIN_CHUNKS_KEY, "3"), 3);
    }

    private String getSetting(String key, String defaultValue) {
        String value = settingsService.getValue(key);
        return value != null && !value.isBlank() ? value : defaultValue;
    }

    private int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private boolean isEnabled() {
        String value = settingsService.getValue(RAG_ENABLED_KEY);
        return value == null || Boolean.parseBoolean(value);
    }
}
