package com.research.assistant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.research.assistant.entity.PaperLayoutArtifactRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** MyBatis access for versioned PDF layout artifacts. */
@Mapper
public interface PaperLayoutArtifactMapper extends BaseMapper<PaperLayoutArtifactRecord> {

    @Select("SELECT id, paper_id, document_hash, parser_version, status, layout_confidence, "
            + "page_count, blocks_json, generated_at, created_at, updated_at "
            + "FROM paper_layout_artifact "
            + "WHERE paper_id = #{paperId} AND document_hash = #{documentHash} "
            + "AND parser_version = #{parserVersion} AND status = 'READY' LIMIT 1")
    PaperLayoutArtifactRecord selectReady(@Param("paperId") Long paperId,
                                          @Param("documentHash") String documentHash,
                                          @Param("parserVersion") String parserVersion);

    @Select("SELECT id, paper_id, document_hash, parser_version, status, layout_confidence, "
            + "page_count, blocks_json, generated_at, created_at, updated_at "
            + "FROM paper_layout_artifact WHERE paper_id = #{paperId} AND status = 'READY' "
            + "ORDER BY generated_at DESC, id DESC LIMIT 1")
    PaperLayoutArtifactRecord selectLatestReady(Long paperId);
}
