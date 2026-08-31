package com.research.assistant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.research.assistant.entity.AgentToolCallRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface AgentToolCallMapper extends BaseMapper<AgentToolCallRecord> {

    @Select("SELECT * FROM agent_tool_call WHERE tool_call_id = #{toolCallId} LIMIT 1")
    AgentToolCallRecord selectByToolCallId(@Param("toolCallId") String toolCallId);

    @Select("SELECT * FROM agent_tool_call WHERE idempotency_key = #{idempotencyKey} LIMIT 1")
    AgentToolCallRecord selectByIdempotencyKey(@Param("idempotencyKey") String idempotencyKey);

    @Select("SELECT * FROM agent_tool_call WHERE run_id = #{runId} ORDER BY ordinal_no")
    List<AgentToolCallRecord> selectByRunId(@Param("runId") String runId);

    @Select("SELECT c.* FROM agent_tool_call c "
            + "JOIN agent_run r ON r.run_id = c.run_id "
            + "JOIN agent_turn t ON t.id = r.turn_id "
            + "WHERE t.session_id = #{sessionId} "
            + "AND c.status = 'COMPLETED' "
            + "AND c.read_only = TRUE "
            + "AND c.tool_name IN ('read_paper_profile', 'retrieve_paper_evidence', 'read_pages') "
            + "AND c.result_json IS NOT NULL AND c.result_json <> '' "
            + "AND r.document_hash = #{documentHash} "
            + "AND r.parser_version = #{parserVersion} "
            + "ORDER BY c.completed_at DESC, c.id DESC LIMIT #{limit}")
    List<AgentToolCallRecord> selectRecentCompletedPaperReads(@Param("sessionId") long sessionId,
                                                               @Param("documentHash") String documentHash,
                                                               @Param("parserVersion") String parserVersion,
                                                               @Param("limit") int limit);

    @Select("SELECT COALESCE(MAX(ordinal_no), 0) FROM agent_tool_call WHERE run_id = #{runId}")
    int selectMaxOrdinal(@Param("runId") String runId);

    @Update("UPDATE agent_tool_call SET status = #{target}, version = version + 1, "
            + "attempt_count = attempt_count + #{attemptIncrement}, "
            + "started_at = CASE WHEN #{target} = 'RUNNING' AND started_at IS NULL THEN CURRENT_TIMESTAMP(6) ELSE started_at END, "
            + "completed_at = CASE WHEN #{terminal} THEN CURRENT_TIMESTAMP(6) ELSE completed_at END, "
            + "result_json = #{resultJson}, error_code = #{errorCode}, error_message = #{errorMessage} "
            + "WHERE id = #{id} AND status = #{expected} AND version = #{version}")
    int transition(@Param("id") long id,
                   @Param("expected") String expected,
                   @Param("target") String target,
                   @Param("version") int version,
                   @Param("terminal") boolean terminal,
                   @Param("attemptIncrement") int attemptIncrement,
                   @Param("resultJson") String resultJson,
                   @Param("errorCode") String errorCode,
                   @Param("errorMessage") String errorMessage);

    @Update("UPDATE agent_tool_call SET action_ticket_hash = #{ticketHash}, "
            + "action_ticket_expires_at = #{expiresAt}, status = 'WAITING_CLIENT', version = version + 1 "
            + "WHERE id = #{id} AND status IN ('REQUESTED','RUNNING','WAITING_CLIENT') AND version = #{version}")
    int storeActionTicket(@Param("id") long id,
                          @Param("version") int version,
                          @Param("ticketHash") String ticketHash,
                          @Param("expiresAt") LocalDateTime expiresAt);
}
