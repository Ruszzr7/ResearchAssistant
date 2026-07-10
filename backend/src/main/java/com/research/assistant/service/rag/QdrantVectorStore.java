package com.research.assistant.service.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.entity.PaperChunk;
import com.research.assistant.mapper.PaperChunkMapper;
import com.research.assistant.service.SettingsService;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.VectorParams;
import io.qdrant.client.grpc.JsonWithInt;
import io.qdrant.client.grpc.Points;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

/**
 * Qdrant 向量存储实现。
 *
 * <p>使用官方 Qdrant Java gRPC 客户端，支持本地/远程 Qdrant 服务。
 * 为了兼容 {@link InMemoryVectorStore} 降级，Qdrant 模式也会把 chunk 文本与 embedding 写入 MySQL
 * 的 {@code paper_chunk} 表。
 */
public class QdrantVectorStore implements VectorStore {

    private static final Logger log = LoggerFactory.getLogger(QdrantVectorStore.class);

    private static final String DEFAULT_COLLECTION = "paper_chunks";
    private static final int UPSERT_BATCH_SIZE = 100;
    private static final Duration OPERATION_TIMEOUT = Duration.ofSeconds(15);

    private final QdrantClient client;
    private final PaperChunkMapper paperChunkMapper;
    private final ObjectMapper objectMapper;
    private final SettingsService settingsService;

    public QdrantVectorStore(SettingsService settingsService,
                             PaperChunkMapper paperChunkMapper,
                             ObjectMapper objectMapper) {
        this.settingsService = settingsService;
        this.paperChunkMapper = paperChunkMapper;
        this.objectMapper = objectMapper;
        this.client = createClient();
    }

    @Override
    public void add(List<EmbeddedChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        // 1. 先写 MySQL，保证内存降级能读到最新数据（即使 Qdrant 不可用）
        persistToMySql(chunks);

        // 2. 批量 upsert 到 Qdrant
        String collection = collectionName();
        int dimension = chunks.get(0).embedding().size();
        ensureCollection(collection, dimension);
        List<Points.PointStruct> points = toPointStructs(chunks);
        for (int i = 0; i < points.size(); i += UPSERT_BATCH_SIZE) {
            List<Points.PointStruct> batch = points.subList(i, Math.min(i + UPSERT_BATCH_SIZE, points.size()));
            try {
                Points.UpdateResult result = client.upsertAsync(collection, batch, OPERATION_TIMEOUT).get();
                log.debug("Qdrant upsert 完成: collection={}, batch={}, status={}", collection, batch.size(), result.getStatus());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new VectorStoreException("Qdrant upsert 被中断", e);
            } catch (ExecutionException e) {
                throw new VectorStoreException("Qdrant upsert 失败: " + e.getCause().getMessage(), e.getCause());
            }
        }
    }

    @Override
    public List<ScoredChunk> findRelevant(List<Float> query, int maxResults, double minScore) {
        if (query == null || query.isEmpty()) {
            return List.of();
        }
        String collection = collectionName();
        if (!collectionExists(collection)) {
            return List.of();
        }
        Points.SearchPoints request = Points.SearchPoints.newBuilder()
                .setCollectionName(collection)
                .addAllVector(query)
                .setLimit(Math.max(1, maxResults))
                .setScoreThreshold((float) minScore)
                .setWithPayload(Points.WithPayloadSelector.newBuilder().setEnable(true).build())
                .build();
        try {
            List<Points.ScoredPoint> results = client.searchAsync(request, OPERATION_TIMEOUT).get();
            return results.stream()
                    .map(this::toScoredChunk)
                    .filter(c -> c != null)
                    .toList();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new VectorStoreException("Qdrant search 被中断", e);
        } catch (ExecutionException e) {
            throw new VectorStoreException("Qdrant search 失败: " + e.getCause().getMessage(), e.getCause());
        }
    }

    @Override
    public void removeByPaperId(Long paperId) {
        if (paperId == null) {
            return;
        }
        String collection = collectionName();
        // 1. 先删 MySQL，保证内存降级不读到已删除数据
        paperChunkMapper.deleteByPaperId(paperId);
        if (!collectionExists(collection)) {
            return;
        }
        Points.Filter filter = Points.Filter.newBuilder()
                .addMust(Points.Condition.newBuilder()
                        .setField(Points.FieldCondition.newBuilder()
                                .setKey("paperId")
                                .setMatch(Points.Match.newBuilder().setInteger(paperId).build())
                                .build())
                        .build())
                .build();
        try {
            client.deleteAsync(collection, filter, OPERATION_TIMEOUT).get();
            log.debug("Qdrant 删除论文 chunk: collection={}, paperId={}", collection, paperId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new VectorStoreException("Qdrant delete 被中断", e);
        } catch (ExecutionException e) {
            throw new VectorStoreException("Qdrant delete 失败: " + e.getCause().getMessage(), e.getCause());
        }
    }

    @PreDestroy
    public void close() {
        if (client != null) {
            try {
                client.close();
            } catch (Exception e) {
                log.warn("关闭 QdrantClient 失败: {}", e.getMessage());
            }
        }
    }

    QdrantClient createClient() {
        String host = settingsService.getValue("qdrant_host");
        if (host == null || host.isBlank()) {
            host = "localhost";
        }
        int port = parseInt(settingsService.getValue("qdrant_port"), 6334);
        boolean useTls = Boolean.parseBoolean(settingsService.getValue("qdrant_use_tls"));
        String apiKey = settingsService.getValue("qdrant_api_key");

        QdrantGrpcClient.Builder grpcBuilder = QdrantGrpcClient.newBuilder(host, port, useTls, false);
        if (apiKey != null && !apiKey.isBlank()) {
            grpcBuilder.withApiKey(apiKey);
        }
        grpcBuilder.withTimeout(OPERATION_TIMEOUT);
        return new QdrantClient(grpcBuilder.build());
    }

    private String collectionName() {
        String name = settingsService.getValue("qdrant_collection");
        return (name == null || name.isBlank()) ? DEFAULT_COLLECTION : name;
    }

    private void ensureCollection(String collection, int dimension) {
        if (collectionExists(collection)) {
            return;
        }
        try {
            VectorParams params = VectorParams.newBuilder()
                    .setSize(dimension)
                    .setDistance(Distance.Cosine)
                    .build();
            client.createCollectionAsync(collection, params, OPERATION_TIMEOUT).get();
            log.info("Qdrant collection 创建成功: {}, dimension={}", collection, dimension);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new VectorStoreException("Qdrant collection 创建被中断", e);
        } catch (ExecutionException e) {
            throw new VectorStoreException("Qdrant collection 创建失败: " + e.getCause().getMessage(), e.getCause());
        }
    }

    private boolean collectionExists(String collection) {
        try {
            return client.collectionExistsAsync(collection, OPERATION_TIMEOUT).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new VectorStoreException("Qdrant collectionExists 被中断", e);
        } catch (ExecutionException e) {
            throw new VectorStoreException("Qdrant collectionExists 失败: " + e.getCause().getMessage(), e.getCause());
        }
    }

    private List<Points.PointStruct> toPointStructs(List<EmbeddedChunk> chunks) {
        List<Points.PointStruct> points = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            EmbeddedChunk c = chunks.get(i);
            String id = deterministicUuid(c.paperId(), c.chunkType(), i).toString();
            Points.Vector vector = Points.Vector.newBuilder().addAllData(c.embedding()).build();
            Points.Vectors vectors = Points.Vectors.newBuilder().setVector(vector).build();
            Map<String, JsonWithInt.Value> payload = Map.of(
                    "paperId", JsonWithInt.Value.newBuilder().setIntegerValue(c.paperId()).build(),
                    "chunkType", JsonWithInt.Value.newBuilder().setStringValue(nonNull(c.chunkType())).build(),
                    "content", JsonWithInt.Value.newBuilder().setStringValue(nonNull(c.content())).build(),
                    "source", JsonWithInt.Value.newBuilder().setStringValue(nonNull(c.source())).build()
            );
            points.add(Points.PointStruct.newBuilder()
                    .setId(Points.PointId.newBuilder().setUuid(id).build())
                    .setVectors(vectors)
                    .putAllPayload(payload)
                    .build());
        }
        return points;
    }

    private void persistToMySql(List<EmbeddedChunk> chunks) {
        for (EmbeddedChunk c : chunks) {
            PaperChunk record = new PaperChunk();
            record.setPaperId(c.paperId());
            record.setChunkType(c.chunkType());
            record.setContent(c.content());
            record.setSource(c.source());
            record.setEmbeddingJson(toJson(c.embedding()));
            paperChunkMapper.insert(record);
        }
    }

    private ScoredChunk toScoredChunk(Points.ScoredPoint point) {
        Map<String, JsonWithInt.Value> payload = point.getPayloadMap();
        Long paperId = null;
        if (payload.containsKey("paperId") && payload.get("paperId").hasIntegerValue()) {
            paperId = payload.get("paperId").getIntegerValue();
        }
        return new ScoredChunk(
                paperId,
                payloadValue(payload, "chunkType"),
                payloadValue(payload, "content"),
                payloadValue(payload, "source"),
                point.getScore()
        );
    }

    private String payloadValue(Map<String, JsonWithInt.Value> payload, String key) {
        JsonWithInt.Value value = payload.get(key);
        return value != null && value.hasStringValue() ? value.getStringValue() : "";
    }

    private String toJson(List<Float> embedding) {
        try {
            return objectMapper.writeValueAsString(embedding);
        } catch (Exception e) {
            log.warn("embedding 序列化失败: {}", e.getMessage());
            return "[]";
        }
    }

    private UUID deterministicUuid(Long paperId, String chunkType, int index) {
        String raw = paperId + "|" + chunkType + "|" + index;
        return UUID.nameUUIDFromBytes(raw.getBytes(StandardCharsets.UTF_8));
    }

    private String nonNull(String value) {
        return value == null ? "" : value;
    }

    private int parseInt(String value, int defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
