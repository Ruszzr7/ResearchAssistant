package com.research.assistant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.research.assistant.entity.PaperMemoryRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** MyBatis access for structured and semantic paper memory revisions. */
@Mapper
public interface PaperMemoryMapper extends BaseMapper<PaperMemoryRecord> {

    String COLUMNS = "id, paper_id, document_hash, layout_parser_version, schema_version, "
            + "status, structure_json, chunk_summaries_json, profile_json, memory_quality_json, "
            + "revision, last_error_code, generated_at, created_at, updated_at";

    @Select("SELECT " + COLUMNS + " FROM paper_memory WHERE paper_id = #{paperId} "
            + "AND document_hash = #{documentHash} "
            + "AND layout_parser_version = #{layoutParserVersion} "
            + "AND schema_version = #{schemaVersion} LIMIT 1")
    PaperMemoryRecord selectVersion(@Param("paperId") Long paperId,
                                    @Param("documentHash") String documentHash,
                                    @Param("layoutParserVersion") String layoutParserVersion,
                                    @Param("schemaVersion") String schemaVersion);

    @Select("SELECT " + COLUMNS + " FROM paper_memory WHERE paper_id = #{paperId} "
            + "ORDER BY updated_at DESC, id DESC LIMIT 1")
    PaperMemoryRecord selectLatest(Long paperId);
}
