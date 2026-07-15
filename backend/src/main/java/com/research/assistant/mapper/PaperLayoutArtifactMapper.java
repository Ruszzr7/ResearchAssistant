package com.research.assistant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.research.assistant.entity.PaperLayoutArtifactRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** MyBatis access for versioned PDF layout artifacts. */
@Mapper
public interface PaperLayoutArtifactMapper extends BaseMapper<PaperLayoutArtifactRecord> {

    @Select("SELECT id, paper_id, document_hash, parser_version, status, layout_confidence, "
            + "page_count, blocks_json, provenance_json, generated_at, created_at, updated_at "
            + "FROM paper_layout_artifact "
            + "WHERE paper_id = #{paperId} AND document_hash = #{documentHash} "
            + "AND parser_version = #{parserVersion} AND status = 'READY' LIMIT 1")
    PaperLayoutArtifactRecord selectReady(@Param("paperId") Long paperId,
                                          @Param("documentHash") String documentHash,
                                          @Param("parserVersion") String parserVersion);

    @Select("SELECT id, paper_id, document_hash, parser_version, status, layout_confidence, "
            + "page_count, blocks_json, provenance_json, generated_at, created_at, updated_at "
            + "FROM paper_layout_artifact WHERE paper_id = #{paperId} AND status = 'READY' "
            + "ORDER BY generated_at DESC, id DESC LIMIT 1")
    PaperLayoutArtifactRecord selectLatestReady(Long paperId);

    /** Latest READY artifact for every paper, used by the aggregate metrics view. */
    @Select("SELECT a.id, a.paper_id, a.document_hash, a.parser_version, a.status, "
            + "a.layout_confidence, a.page_count, a.blocks_json, a.provenance_json, "
            + "a.generated_at, a.created_at, a.updated_at "
            + "FROM paper_layout_artifact a WHERE a.status = 'READY' "
            + "AND a.id = (SELECT MAX(b.id) FROM paper_layout_artifact b "
            + "WHERE b.paper_id = a.paper_id AND b.status = 'READY') "
            + "ORDER BY a.paper_id ASC")
    List<PaperLayoutArtifactRecord> selectLatestReadyForMetrics();
}
