package com.research.assistant.service.rag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.entity.PaperChunk;
import com.research.assistant.mapper.PaperChunkMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 内存向量存储 —— 启动时从 `paper_chunk` 表加载，运行时增量更新。
 * <p>
 * 作为 RAG MVP 实现，避免引入 pgvector/Qdrant 等外部依赖；数据量增大后可替换为专用向量数据库。
 * 默认由 {@link VectorStoreConfig} 根据 {@code vector_store_provider} 决定是否暴露为 primary bean。
 */
public class InMemoryVectorStore implements VectorStore {

    private static final Logger log = LoggerFactory.getLogger(InMemoryVectorStore.class);

    private final PaperChunkMapper paperChunkMapper;
    private final ObjectMapper objectMapper;

    private final List<Entry> entries = new CopyOnWriteArrayList<>();

    public InMemoryVectorStore(PaperChunkMapper paperChunkMapper, ObjectMapper objectMapper) {
        this.paperChunkMapper = paperChunkMapper;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void load() {
        try {
            List<PaperChunk> records = paperChunkMapper.selectList(null);
            for (PaperChunk record : records) {
                List<Float> vector = parseEmbedding(record.getEmbeddingJson());
                if (vector != null && !vector.isEmpty()) {
                    entries.add(new Entry(record.getPaperId(), record.getChunkType(), record.getContent(), record.getSource(), vector));
                }
            }
            log.info("已从数据库加载 {} 条向量分片", entries.size());
        } catch (Exception e) {
            log.warn("加载向量分片失败: {}", e.getMessage());
        }
    }

    @Override
    public void add(List<EmbeddedChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        for (EmbeddedChunk chunk : chunks) {
            PaperChunk record = new PaperChunk();
            record.setPaperId(chunk.paperId());
            record.setChunkType(chunk.chunkType());
            record.setContent(chunk.content());
            record.setSource(chunk.source());
            record.setEmbeddingJson(toJson(chunk.embedding()));
            paperChunkMapper.insert(record);
            entries.add(new Entry(chunk.paperId(), chunk.chunkType(), chunk.content(), chunk.source(), chunk.embedding()));
        }
    }

    @Override
    public List<ScoredChunk> findRelevant(List<Float> query, int maxResults, double minScore) {
        if (query == null || query.isEmpty()) {
            return List.of();
        }
        double[] queryArray = toArray(query);
        List<ScoredChunk> scored = new ArrayList<>();
        for (Entry e : entries) {
            double score = cosineSimilarity(queryArray, toArray(e.embedding));
            if (score >= minScore) {
                scored.add(new ScoredChunk(e.paperId, e.chunkType, e.content, e.source, score));
            }
        }
        scored.sort(Comparator.comparingDouble(ScoredChunk::score).reversed());
        return scored.size() > maxResults ? scored.subList(0, maxResults) : scored;
    }

    @Override
    public void removeByPaperId(Long paperId) {
        if (paperId == null) return;
        paperChunkMapper.deleteByPaperId(paperId);
        entries.removeIf(e -> paperId.equals(e.paperId));
    }

    private double cosineSimilarity(double[] a, double[] b) {
        if (a.length != b.length || a.length == 0) {
            return 0;
        }
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0 || normB == 0) {
            return 0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    private double[] toArray(List<Float> list) {
        double[] arr = new double[list.size()];
        for (int i = 0; i < list.size(); i++) {
            arr[i] = list.get(i);
        }
        return arr;
    }

    private String toJson(List<Float> embedding) {
        try {
            return objectMapper.writeValueAsString(embedding);
        } catch (Exception e) {
            log.warn("embedding 序列化失败: {}", e.getMessage());
            return "[]";
        }
    }

    private List<Float> parseEmbedding(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<Float>>() {});
        } catch (Exception e) {
            log.warn("embedding 反序列化失败: {}", e.getMessage());
            return List.of();
        }
    }

    private record Entry(Long paperId, String chunkType, String content, String source, List<Float> embedding) {
    }
}
