package com.research.assistant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.research.assistant.entity.PaperConversationTurnRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface PaperConversationTurnMapper extends BaseMapper<PaperConversationTurnRecord> {

    String COLUMNS = "id, paper_id, conversation_id, source_run_id, document_hash, parser_version, "
            + "question, answer, selection_block_ids_json, claims_json, evidence_refs_json, created_at";

    @Select("SELECT " + COLUMNS + " FROM paper_conversation_turn WHERE source_run_id = #{runId} LIMIT 1")
    PaperConversationTurnRecord selectBySourceRunId(@Param("runId") String runId);

    @Select("SELECT " + COLUMNS + " FROM (SELECT " + COLUMNS
            + " FROM paper_conversation_turn WHERE paper_id = #{paperId} "
            + "AND conversation_id = #{conversationId} AND document_hash = #{documentHash} "
            + "AND parser_version = #{parserVersion} ORDER BY created_at DESC, id DESC LIMIT #{limit}) recent "
            + "ORDER BY created_at ASC, id ASC")
    List<PaperConversationTurnRecord> selectRecentConversation(
            @Param("paperId") long paperId,
            @Param("conversationId") String conversationId,
            @Param("documentHash") String documentHash,
            @Param("parserVersion") String parserVersion,
            @Param("limit") int limit);
}
