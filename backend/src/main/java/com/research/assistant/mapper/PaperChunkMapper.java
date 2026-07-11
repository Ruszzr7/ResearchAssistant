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

    @Select("SELECT id, paper_id, chunk_type, content, embedding_json, source, created_at " +
            "FROM paper_chunk WHERE paper_id = #{paperId} ORDER BY id")
    List<PaperChunk> selectByPaperId(Long paperId);

    @Insert("<script>INSERT INTO paper_chunk " +
            "(paper_id, chunk_type, content, embedding_json, source) VALUES " +
            "<foreach collection='chunks' item='chunk' separator=','>" +
            "(#{chunk.paperId}, #{chunk.chunkType}, #{chunk.content}, #{chunk.embeddingJson}, #{chunk.source})" +
            "</foreach></script>")
    int insertBatch(@Param("chunks") List<PaperChunk> chunks);

    @Delete("DELETE FROM paper_chunk WHERE paper_id = #{paperId}")
    int deleteByPaperId(Long paperId);
}
