package com.research.assistant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.research.assistant.entity.AgentTurnRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AgentTurnMapper extends BaseMapper<AgentTurnRecord> {

    @Select("SELECT id FROM research_session WHERE id = #{sessionId} FOR UPDATE")
    Long lockSession(@Param("sessionId") long sessionId);

    @Select("SELECT * FROM agent_turn WHERE turn_id = #{turnId} LIMIT 1")
    AgentTurnRecord selectByTurnId(@Param("turnId") String turnId);

    @Select("SELECT * FROM agent_turn WHERE session_id = #{sessionId} "
            + "AND status IN ('QUEUED','RUNNING','WAITING_USER','WAITING_CLIENT') "
            + "ORDER BY sequence_no DESC LIMIT 1")
    AgentTurnRecord selectActiveBySession(@Param("sessionId") long sessionId);

    @Select("SELECT * FROM agent_turn WHERE session_id = #{sessionId} "
            + "AND client_request_id = #{clientRequestId} LIMIT 1")
    AgentTurnRecord selectByClientRequest(@Param("sessionId") long sessionId,
                                          @Param("clientRequestId") String clientRequestId);

    @Select("SELECT COALESCE(MAX(sequence_no), 0) FROM agent_turn WHERE session_id = #{sessionId}")
    long selectMaxSequence(@Param("sessionId") long sessionId);

    @Select("SELECT COUNT(*) FROM agent_turn WHERE session_id = #{sessionId}")
    long countBySessionId(@Param("sessionId") long sessionId);

    @Update("UPDATE agent_turn SET status = #{target}, version = version + 1, "
            + "started_at = CASE WHEN #{target} = 'RUNNING' AND started_at IS NULL THEN CURRENT_TIMESTAMP(6) ELSE started_at END, "
            + "completed_at = CASE WHEN #{terminal} THEN CURRENT_TIMESTAMP(6) ELSE completed_at END, "
            + "error_code = #{errorCode}, error_message = #{errorMessage} "
            + "WHERE id = #{id} AND status = #{expected} AND version = #{version}")
    int transition(@Param("id") long id,
                   @Param("expected") String expected,
                   @Param("target") String target,
                   @Param("version") int version,
                   @Param("terminal") boolean terminal,
                   @Param("errorCode") String errorCode,
                   @Param("errorMessage") String errorMessage);

    @Update("UPDATE agent_turn SET final_message_key = #{messageKey} WHERE id = #{id} AND final_message_key IS NULL")
    int bindFinalMessage(@Param("id") long id, @Param("messageKey") String messageKey);
}
