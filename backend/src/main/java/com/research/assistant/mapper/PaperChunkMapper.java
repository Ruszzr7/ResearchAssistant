package com.research.assistant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.research.assistant.entity.PaperChunk;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * RAG 文档分片 Mapper。
 */
@Mapper
public interface PaperChunkMapper extends BaseMapper<PaperChunk> {

    @Select("SELECT id, paper_id, index_version, chunk_type, content, embedding_json, source, created_at " +
            "FROM paper_chunk WHERE paper_id = #{paperId} ORDER BY id")
    List<PaperChunk> selectByPaperId(Long paperId);

    @Select("SELECT pc.id, pc.paper_id, pc.index_version, pc.chunk_type, pc.content, "
            + "pc.embedding_json, pc.source, pc.created_at "
            + "FROM paper_chunk pc "
            + "JOIN rag_index_state s ON s.paper_id = pc.paper_id "
            + "JOIN rag_index_version v ON v.paper_id = pc.paper_id "
            + "AND v.version_no = s.active_version AND v.status = 'ACTIVE' "
            + "AND pc.index_version = s.active_version "
            + "WHERE pc.paper_id = #{paperId} ORDER BY pc.id")
    List<PaperChunk> selectActiveByPaperId(Long paperId);

    @Select("SELECT pc.id, pc.paper_id, pc.index_version, pc.chunk_type, pc.content, "
            + "pc.embedding_json, pc.source, pc.created_at "
            + "FROM paper_chunk pc "
            + "JOIN rag_index_state s ON s.paper_id = pc.paper_id "
            + "JOIN rag_index_version v ON v.paper_id = pc.paper_id "
            + "AND v.version_no = s.active_version AND v.status = 'ACTIVE' "
            + "AND pc.index_version = s.active_version "
            + "ORDER BY pc.id")
    List<PaperChunk> selectAllActive();

    @Insert("<script>INSERT INTO paper_chunk " +
            "(paper_id, index_version, chunk_type, content, embedding_json, source) VALUES " +
            "<foreach collection='chunks' item='chunk' separator=','>" +
            "(#{chunk.paperId}, #{chunk.indexVersion}, #{chunk.chunkType}, #{chunk.content}, "
            + "#{chunk.embeddingJson}, #{chunk.source})" +
            "</foreach></script>")
    int insertBatch(@Param("chunks") List<PaperChunk> chunks);

    @Delete("DELETE FROM paper_chunk WHERE paper_id = #{paperId}")
    int deleteByPaperId(Long paperId);

    @Delete("DELETE FROM paper_chunk WHERE paper_id = #{paperId} AND index_version = #{indexVersion}")
    int deleteByVersion(@Param("paperId") Long paperId, @Param("indexVersion") Integer indexVersion);

    @Delete("DELETE FROM paper_chunk WHERE paper_id = #{paperId} AND index_version IN "
            + "(SELECT version_no FROM rag_index_version WHERE paper_id = #{paperId} "
            + "AND status IN ('RETIRED', 'FAILED') AND updated_at < #{before})")
    int deleteOldVersions(@Param("paperId") Long paperId,
                          @Param("before") java.time.LocalDateTime before);
}
