package com.research.assistant.service.rag;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.entity.PaperChunk;
import com.research.assistant.mapper.PaperChunkMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** 统一负责 paper_chunk 持久化，避免不同向量实现重复写库。 */
@Component
public class PaperChunkPersistence {

    private static final Logger log = LoggerFactory.getLogger(PaperChunkPersistence.class);

    private final PaperChunkMapper mapper;
    private final ObjectMapper objectMapper;

    public PaperChunkPersistence(PaperChunkMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    public void saveAll(List<EmbeddedChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        List<PaperChunk> records = new ArrayList<>(chunks.size());
        for (EmbeddedChunk chunk : chunks) {
            PaperChunk record = new PaperChunk();
            record.setPaperId(chunk.paperId());
            record.setChunkType(chunk.chunkType());
            record.setContent(chunk.content());
            record.setSource(chunk.source());
            record.setEmbeddingJson(toJson(chunk.embedding()));
            records.add(record);
        }
        // 单条保留原 insert，批量索引时使用一次 SQL 减少数据库往返。
        if (records.size() == 1) {
            mapper.insert(records.get(0));
        } else {
            mapper.insertBatch(records);
        }
    }

    public void deleteByPaperId(Long paperId) {
        if (paperId != null) {
            mapper.deleteByPaperId(paperId);
        }
    }

    private String toJson(List<Float> embedding) {
        try {
            return objectMapper.writeValueAsString(embedding);
        } catch (JsonProcessingException e) {
            log.warn("embedding 序列化失败: {}", e.getMessage());
            return "[]";
        }
    }
}
