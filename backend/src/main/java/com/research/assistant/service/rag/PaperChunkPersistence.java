package com.research.assistant.service.rag;

import com.research.assistant.entity.PaperChunk;
import com.research.assistant.mapper.PaperChunkMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** Persists versioned local text chunks; embedding_json remains an empty legacy column. */
@Component
public class PaperChunkPersistence {

    private final PaperChunkMapper mapper;

    public PaperChunkPersistence(PaperChunkMapper mapper) {
        this.mapper = mapper;
    }

    public void saveAll(List<DocumentChunk> chunks) {
        saveAll(chunks, 1);
    }

    public void saveAll(List<DocumentChunk> chunks, int indexVersion) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        List<PaperChunk> records = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            DocumentChunk chunk = chunks.get(i);
            PaperChunk record = new PaperChunk();
            record.setPaperId(chunk.paperId());
            record.setIndexVersion(indexVersion);
            record.setChunkKey(RagChunkIdentity.chunkKey(chunk.paperId(), indexVersion,
                    chunk.chunkOrder() == null ? i : chunk.chunkOrder(), chunk.content()));
            record.setSourceType(chunk.sourceType());
            record.setChunkOrder(i);
            record.setPageStart(chunk.pageStart());
            record.setPageEnd(chunk.pageEnd());
            record.setCharStart(chunk.charStart());
            record.setCharEnd(chunk.charEnd());
            record.setChunkType(chunk.chunkType());
            record.setContent(chunk.content());
            record.setSource(chunk.source());
            record.setEmbeddingJson("[]");
            record.setContentHash(RagChunkIdentity.contentHash(chunk.content()));
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

    public void deleteByVersion(Long paperId, int indexVersion) {
        if (paperId != null) {
            mapper.deleteByVersion(paperId, indexVersion);
        }
    }

}
