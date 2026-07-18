package com.research.assistant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.research.assistant.entity.PaperMemoryObservationRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface PaperMemoryObservationMapper extends BaseMapper<PaperMemoryObservationRecord> {

    String COLUMNS = "id, paper_id, document_hash, parser_version, claim_fingerprint, claim_text, "
            + "evidence_refs_json, source_run_id, source_conversation_id, confirmation_count, status, "
            + "first_seen_at, last_confirmed_at, created_at, updated_at";

    @Select("SELECT " + COLUMNS + " FROM paper_memory_observation WHERE paper_id = #{paperId} "
            + "AND document_hash = #{documentHash} AND parser_version = #{parserVersion} "
            + "AND claim_fingerprint = #{fingerprint} LIMIT 1")
    PaperMemoryObservationRecord selectVersionClaim(
            @Param("paperId") long paperId,
            @Param("documentHash") String documentHash,
            @Param("parserVersion") String parserVersion,
            @Param("fingerprint") String fingerprint);

    @Select("SELECT " + COLUMNS + " FROM paper_memory_observation WHERE paper_id = #{paperId} "
            + "AND document_hash = #{documentHash} AND parser_version = #{parserVersion} "
            + "AND status = 'ACTIVE' ORDER BY last_confirmed_at DESC, id DESC LIMIT #{limit}")
    List<PaperMemoryObservationRecord> selectRecentVersion(
            @Param("paperId") long paperId,
            @Param("documentHash") String documentHash,
            @Param("parserVersion") String parserVersion,
            @Param("limit") int limit);
}
