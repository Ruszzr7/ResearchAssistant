package com.research.assistant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.research.assistant.entity.Conversation;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 对话历史 Mapper。
 */
@Mapper
public interface ConversationMapper extends BaseMapper<Conversation> {

    @Select("SELECT id, memory_id, role, content, created_at FROM conversation " +
            "WHERE memory_id = #{memoryId} ORDER BY created_at ASC, id ASC")
    List<Conversation> selectByMemoryId(@Param("memoryId") String memoryId);

    @Delete("DELETE FROM conversation WHERE memory_id = #{memoryId}")
    int deleteByMemoryId(@Param("memoryId") String memoryId);
}
