package com.research.assistant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.research.assistant.entity.ResearchSession;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ResearchSessionMapper extends BaseMapper<ResearchSession> {

    @Select("SELECT id, session_key, title, session_type, primary_paper_id, last_page, mode, "
            + "output_language, archived, last_activity_at, created_at, updated_at "
            + "FROM research_session WHERE archived = #{archived} "
            + "AND (#{keyword} IS NULL OR title LIKE CONCAT('%', #{keyword}, '%')) "
            + "ORDER BY last_activity_at DESC, id DESC LIMIT #{limit}")
    List<ResearchSession> selectRecent(@Param("archived") boolean archived,
                                       @Param("keyword") String keyword,
                                       @Param("limit") int limit);

    @Select("SELECT id, session_key, title, session_type, primary_paper_id, last_page, mode, "
            + "output_language, archived, last_activity_at, created_at, updated_at "
            + "FROM research_session WHERE session_key = #{sessionKey} LIMIT 1")
    ResearchSession selectBySessionKey(@Param("sessionKey") String sessionKey);
}
