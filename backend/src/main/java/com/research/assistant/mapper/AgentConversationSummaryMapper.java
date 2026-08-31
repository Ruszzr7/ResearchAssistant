package com.research.assistant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.research.assistant.entity.AgentConversationSummaryRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AgentConversationSummaryMapper extends BaseMapper<AgentConversationSummaryRecord> {
    @Select("SELECT * FROM agent_conversation_summary WHERE session_id = #{sessionId} "
            + "ORDER BY revision DESC LIMIT 1")
    AgentConversationSummaryRecord selectLatest(@Param("sessionId") long sessionId);
}
