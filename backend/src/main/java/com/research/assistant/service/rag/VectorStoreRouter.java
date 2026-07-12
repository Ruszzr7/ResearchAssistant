package com.research.assistant.service.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 向量存储路由 —— 根据配置在 Qdrant 与内存实现之间切换，并负责失败降级。
 */
public class VectorStoreRouter implements VectorStore {

    private static final Logger log = LoggerFactory.getLogger(VectorStoreRouter.class);

    private final InMemoryVectorStore memory;
    private final QdrantVectorStore qdrant;
    private final boolean qdrantEnabled;
    private final RagIndexVersionService versionService;
    private final AtomicBoolean degraded = new AtomicBoolean();

    public VectorStoreRouter(InMemoryVectorStore memory, QdrantVectorStore qdrant, boolean qdrantEnabled) {
        this(memory, qdrant, qdrantEnabled, null);
    }

    public VectorStoreRouter(InMemoryVectorStore memory, QdrantVectorStore qdrant,
                             boolean qdrantEnabled, RagIndexVersionService versionService) {
        this.memory = memory;
        this.qdrant = qdrant;
        this.qdrantEnabled = qdrantEnabled;
        this.versionService = versionService;
    }

    @Override
    public void add(List<EmbeddedChunk> chunks) {
        degraded.set(false);
        if (qdrantEnabled) {
            try {
                qdrant.add(chunks);
                return;
            } catch (VectorStoreException e) {
                degraded.set(true);
                log.warn("Qdrant add 失败，降级到内存向量存储: {}", e.getMessage());
                // Qdrant.add 已先写入 MySQL，加载持久化结果即可，不能再次追加同一批 chunk。
                boolean wasLoaded = memory.isLoaded();
                memory.load();
                if (wasLoaded || !memory.isLoaded()) {
                    memory.addInMemory(chunks);
                }
                return;
            }
        }
        memory.add(chunks);
    }

    @Override
    public void replacePaperIndex(Long paperId, int indexVersion, List<EmbeddedChunk> chunks) {
        degraded.set(false);
        if (qdrantEnabled) {
            try {
                qdrant.replacePaperIndex(paperId, indexVersion, chunks);
            } catch (VectorStoreException e) {
                degraded.set(true);
                log.warn("Qdrant 版本切换失败，降级到内存向量存储: {}", e.getMessage());
            }
        }
        // 内存使用 copy-on-write，始终保持当前数据库 active 版本可检索。
        memory.replacePaperIndex(paperId, indexVersion, chunks);
    }

    @Override
    public List<ScoredChunk> findRelevant(List<Float> query, int maxResults, double minScore) {
        degraded.set(false);
        if (qdrantEnabled) {
            try {
                List<ScoredChunk> results = qdrant.findRelevant(query, maxResults, minScore);
                List<ScoredChunk> activeResults = filterActiveVersions(results);
                if (!results.isEmpty() && activeResults.isEmpty()) {
                    degraded.set(true);
                    memory.load();
                    return memory.findRelevant(query, maxResults, minScore);
                }
                return activeResults;
            } catch (VectorStoreException e) {
                degraded.set(true);
                log.warn("Qdrant findRelevant 失败，降级到内存向量存储: {}", e.getMessage());
            }
        }
        memory.load();
        return memory.findRelevant(query, maxResults, minScore);
    }

    @Override
    public void removeByPaperId(Long paperId) {
        degraded.set(false);
        if (qdrantEnabled) {
            try {
                qdrant.removeByPaperId(paperId);
            } catch (VectorStoreException e) {
                degraded.set(true);
                log.warn("Qdrant removeByPaperId 失败，仍执行内存清理: {}", e.getMessage());
            }
        }
        memory.load();
        memory.removeByPaperId(paperId);
    }

    @Override
    public boolean lastOperationDegraded() {
        return degraded.get();
    }

    private List<ScoredChunk> filterActiveVersions(List<ScoredChunk> results) {
        if (results == null || results.isEmpty() || versionService == null) {
            return results == null ? List.of() : results;
        }
        Map<Long, Integer> activeVersions = new HashMap<>();
        versionService.activeVersions().forEach(version -> {
            if (version.getPaperId() != null && version.getVersionNo() != null) {
                activeVersions.put(version.getPaperId(), version.getVersionNo());
            }
        });
        if (activeVersions.isEmpty()) {
            return results;
        }
        return results.stream()
                .filter(chunk -> chunk.paperId() == null
                        || chunk.indexVersion() == null
                        || chunk.indexVersion().equals(activeVersions.get(chunk.paperId())))
                .toList();
    }
}
