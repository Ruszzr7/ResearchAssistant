package com.research.assistant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.research.assistant.entity.ResearchMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface ResearchMessageMapper extends BaseMapper<ResearchMessage> {

    @Select("SELECT id, session_id, message_key, role, message_type, message_status, content, run_id, "
            + "agent_turn_id, selection_anchor_json, evidence_json, evidence_schema_version, created_at "
            + "FROM research_message WHERE session_id = #{sessionId} "
            + "ORDER BY created_at ASC, id ASC")
    List<ResearchMessage> selectBySessionId(@Param("sessionId") long sessionId);

    @Select("SELECT COUNT(*) FROM research_message WHERE session_id = #{sessionId}")
    long countBySessionId(@Param("sessionId") long sessionId);

    @Select("SELECT id, session_id, message_key, role, message_type, message_status, content, run_id, "
            + "agent_turn_id, selection_anchor_json, evidence_json, evidence_schema_version, created_at FROM research_message "
            + "WHERE session_id = #{sessionId} AND message_key = #{messageKey} LIMIT 1")
    ResearchMessage selectByMessageKey(@Param("sessionId") long sessionId,
                                       @Param("messageKey") String messageKey);

    @Select("SELECT id, session_id, message_key, role, message_type, message_status, content, run_id, "
            + "agent_turn_id, selection_anchor_json, evidence_json, evidence_schema_version, created_at "
            + "FROM research_message rm WHERE session_id = #{sessionId} AND message_status = 'FINAL' "
            + "AND (role <> 'USER' OR run_id IS NULL OR EXISTS (SELECT 1 FROM agent_run ar "
            + "WHERE ar.run_id = rm.run_id AND ar.status IN ('COMPLETED','WAITING_USER','WAITING_CLIENT'))) "
            + "ORDER BY created_at DESC, id DESC LIMIT #{limit}")
    List<ResearchMessage> selectRecentFinal(@Param("sessionId") long sessionId,
                                            @Param("limit") int limit);

    @Select("SELECT id, session_id, message_key, role, message_type, message_status, content, run_id, "
            + "agent_turn_id, selection_anchor_json, evidence_json, evidence_schema_version, created_at "
            + "FROM research_message rm WHERE session_id = #{sessionId} AND message_status = 'FINAL' "
            + "AND id > #{afterId} "
            + "AND (role <> 'USER' OR run_id IS NULL OR EXISTS (SELECT 1 FROM agent_run ar "
            + "WHERE ar.run_id = rm.run_id AND ar.status IN ('COMPLETED','WAITING_USER','WAITING_CLIENT'))) "
            + "ORDER BY created_at ASC, id ASC")
    List<ResearchMessage> selectFinalAfter(@Param("sessionId") long sessionId,
                                           @Param("afterId") long afterId);

    @Select("SELECT id, session_id, message_key, role, message_type, message_status, content, run_id, "
            + "agent_turn_id, selection_anchor_json, evidence_json, evidence_schema_version, created_at "
            + "FROM research_message WHERE run_id = #{runId} AND role = 'ASSISTANT' "
            + "ORDER BY created_at DESC, id DESC LIMIT 1")
    ResearchMessage selectLatestAssistantByRun(@Param("runId") String runId);

    @Update("UPDATE research_message SET run_id = #{runId} WHERE id = #{id} AND run_id IS NULL")
    int bindRun(@Param("id") long id, @Param("runId") String runId);
}
