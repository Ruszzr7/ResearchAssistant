package com.research.assistant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.research.assistant.entity.ResearchMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ResearchMessageMapper extends BaseMapper<ResearchMessage> {

    @Select("SELECT id, session_id, message_key, role, content, run_id, selection_anchor_json, "
            + "evidence_json, created_at FROM research_message WHERE session_id = #{sessionId} "
            + "ORDER BY created_at ASC, id ASC")
    List<ResearchMessage> selectBySessionId(@Param("sessionId") long sessionId);

    @Select("SELECT COUNT(*) FROM research_message WHERE session_id = #{sessionId}")
    long countBySessionId(@Param("sessionId") long sessionId);

    @Select("SELECT id, session_id, message_key, role, content, run_id, selection_anchor_json, "
            + "evidence_json, created_at FROM research_message "
            + "WHERE session_id = #{sessionId} AND message_key = #{messageKey} LIMIT 1")
    ResearchMessage selectByMessageKey(@Param("sessionId") long sessionId,
                                       @Param("messageKey") String messageKey);
}
