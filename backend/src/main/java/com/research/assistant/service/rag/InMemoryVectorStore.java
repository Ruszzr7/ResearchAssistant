package com.research.assistant.service.rag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.entity.PaperChunk;
import com.research.assistant.mapper.PaperChunkMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 内存向量存储 —— 启动时从 `paper_chunk` 表加载，运行时增量更新。
 * <p>
 * 作为 RAG MVP 实现，避免引入 pgvector/Qdrant 等外部依赖；数据量增大后可替换为专用向量数据库。
 * 默认由 {@link VectorStoreConfig} 根据 {@code vector_store_provider} 决定是否暴露为 primary bean。
 */
public class InMemoryVectorStore implements VectorStore {

    private static final Logger log = LoggerFactory.getLogger(InMemoryVectorStore.class);

    private final PaperChunkPersistence persistence;
    private final PaperChunkMapper paperChunkMapper;
    private final ObjectMapper objectMapper;

    private final List<Entry> entries = new ArrayList<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final AtomicBoolean loaded = new AtomicBoolean();

    public InMemoryVectorStore(PaperChunkMapper paperChunkMapper, ObjectMapper objectMapper) {
        this(new PaperChunkPersistence(paperChunkMapper, objectMapper), paperChunkMapper, objectMapper);
    }

    public InMemoryVectorStore(PaperChunkPersistence persistence,
                               PaperChunkMapper paperChunkMapper,
                               ObjectMapper objectMapper) {
        this.persistence = persistence;
        this.paperChunkMapper = paperChunkMapper;
        this.objectMapper = objectMapper;
    }

    public void load() {
        if (!loaded.compareAndSet(false, true)) {
            return;
        }
        try {
            List<PaperChunk> records = paperChunkMapper.selectAllActive();
            if (records == null) {
                records = List.of();
            }
            List<Entry> loadedEntries = new ArrayList<>(records.size());
            for (PaperChunk record : records) {
                List<Float> vector = parseEmbedding(record.getEmbeddingJson());
                if (vector != null && !vector.isEmpty()) {
                    loadedEntries.add(new Entry(record.getPaperId(), record.getChunkType(), record.getContent(),
                            record.getSource(), vector, record.getChunkKey(), record.getIndexVersion(),
                            record.getSourceType(), record.getPageStart(), record.getPageEnd(),
                            record.getCharStart(), record.getCharEnd()));
                }
            }
            lock.writeLock().lock();
            try {
                entries.clear();
                entries.addAll(loadedEntries);
            } finally {
                lock.writeLock().unlock();
            }
            log.info("已从数据库加载 {} 条向量分片", entries.size());
        } catch (Exception e) {
            loaded.set(false);
            log.warn("加载向量分片失败: {}", e.getMessage());
        }
    }

    boolean isLoaded() {
        return loaded.get();
    }

    @Override
    public void add(List<EmbeddedChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        persistence.saveAll(chunks);
        addInMemory(chunks);
    }

    @Override
    public void replacePaperIndex(Long paperId, int indexVersion, List<EmbeddedChunk> chunks) {
        if (paperId == null || chunks == null || chunks.isEmpty()) {
            return;
        }
        lock.writeLock().lock();
        try {
            entries.removeIf(e -> paperId.equals(e.paperId));
            for (int i = 0; i < chunks.size(); i++) {
                EmbeddedChunk chunk = chunks.get(i);
                String chunkKey = chunk.chunkKey() == null || chunk.chunkKey().isBlank()
                        ? RagChunkIdentity.chunkKey(chunk.paperId(), indexVersion, i, chunk.content())
                        : chunk.chunkKey();
                entries.add(new Entry(chunk.paperId(), chunk.chunkType(), chunk.content(),
                        chunk.source(), chunk.embedding(), chunkKey, indexVersion, chunk.sourceType(),
                        chunk.pageStart(), chunk.pageEnd(), chunk.charStart(), chunk.charEnd()));
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Qdrant 失败降级时只更新内存，避免重复写入 paper_chunk。 */
    void addInMemory(List<EmbeddedChunk> chunks) {
        lock.writeLock().lock();
        try {
            for (int i = 0; i < chunks.size(); i++) {
                EmbeddedChunk chunk = chunks.get(i);
                int version = chunk.indexVersion() == null ? 1 : chunk.indexVersion();
                String chunkKey = chunk.chunkKey() == null || chunk.chunkKey().isBlank()
                        ? RagChunkIdentity.chunkKey(chunk.paperId(), version, i, chunk.content())
                        : chunk.chunkKey();
                entries.add(new Entry(chunk.paperId(), chunk.chunkType(), chunk.content(), chunk.source(),
                        chunk.embedding(), chunkKey, version, chunk.sourceType(), chunk.pageStart(),
                        chunk.pageEnd(), chunk.charStart(), chunk.charEnd()));
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public List<ScoredChunk> findRelevant(List<Float> query, int maxResults, double minScore) {
        if (query == null || query.isEmpty()) {
            return List.of();
        }
        double[] queryArray = toArray(query);
        List<ScoredChunk> scored = new ArrayList<>();
        lock.readLock().lock();
        try {
            for (Entry e : entries) {
                double score = cosineSimilarity(queryArray, e.embeddingArray);
                if (score >= minScore) {
                    scored.add(new ScoredChunk(e.paperId, e.chunkType, e.content, e.source, score,
                            e.chunkKey, e.indexVersion, e.sourceType, e.pageStart, e.pageEnd,
                            e.charStart, e.charEnd, null));
                }
            }
        } finally {
            lock.readLock().unlock();
        }
        scored.sort(Comparator.comparingDouble(ScoredChunk::score).reversed());
        int safeMaxResults = Math.max(1, maxResults);
        return scored.size() > safeMaxResults ? scored.subList(0, safeMaxResults) : scored;
    }

    @Override
    public void removeByPaperId(Long paperId) {
        if (paperId == null) return;
        persistence.deleteByPaperId(paperId);
        removeFromMemory(paperId);
    }

    /** 路由层已完成持久化删除时使用，避免 Qdrant + 内存双写 MySQL。 */
    void removeFromMemory(Long paperId) {
        if (paperId == null) return;
        lock.writeLock().lock();
        try {
            entries.removeIf(e -> paperId.equals(e.paperId));
        } finally {
            lock.writeLock().unlock();
        }
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

    private record Entry(Long paperId, String chunkType, String content, String source,
                         List<Float> embedding, double[] embeddingArray, String chunkKey,
                         Integer indexVersion, String sourceType, Integer pageStart, Integer pageEnd,
                         Integer charStart, Integer charEnd) {
        Entry(Long paperId, String chunkType, String content, String source, List<Float> embedding,
              String chunkKey, Integer indexVersion, String sourceType, Integer pageStart,
              Integer pageEnd, Integer charStart, Integer charEnd) {
            this(paperId, chunkType, content, source, embedding, toArrayStatic(embedding), chunkKey,
                    indexVersion, sourceType, pageStart, pageEnd, charStart, charEnd);
        }

        private static double[] toArrayStatic(List<Float> list) {
            double[] arr = new double[list.size()];
            for (int i = 0; i < list.size(); i++) {
                arr[i] = list.get(i);
            }
            return arr;
        }
    }
}
