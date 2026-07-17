package com.research.assistant.mapper;

import com.research.assistant.dto.research.ResearchPaperView;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ResearchSessionPaperMapper {

    @Insert("INSERT IGNORE INTO research_session_paper(session_id, paper_id, position_no) "
            + "VALUES(#{sessionId}, #{paperId}, #{position})")
    int insertLink(@Param("sessionId") long sessionId,
                   @Param("paperId") long paperId,
                   @Param("position") int position);

    @Delete("DELETE FROM research_session_paper WHERE session_id = #{sessionId}")
    int deleteBySessionId(@Param("sessionId") long sessionId);

    @Select("SELECT p.id, p.title, p.year, p.source, rsp.position_no "
            + "FROM research_session_paper rsp JOIN paper p ON p.id = rsp.paper_id "
            + "WHERE rsp.session_id = #{sessionId} ORDER BY rsp.position_no, p.id")
    List<ResearchPaperView> selectPapers(@Param("sessionId") long sessionId);

    @Select("SELECT COUNT(*) FROM research_session_paper WHERE session_id = #{sessionId} AND paper_id = #{paperId}")
    long countLink(@Param("sessionId") long sessionId, @Param("paperId") long paperId);
}
