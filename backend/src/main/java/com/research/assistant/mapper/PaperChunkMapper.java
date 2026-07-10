package com.research.assistant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.research.assistant.entity.PaperChunk;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * RAG 文档分片 Mapper。
 */
@Mapper
public interface PaperChunkMapper extends BaseMapper<PaperChunk> {

    @Select("SELECT * FROM paper_chunk WHERE paper_id = #{paperId} ORDER BY id")
    List<PaperChunk> selectByPaperId(Long paperId);

    @Delete("DELETE FROM paper_chunk WHERE paper_id = #{paperId}")
    int deleteByPaperId(Long paperId);
}
