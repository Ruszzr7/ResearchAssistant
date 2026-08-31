package com.research.assistant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.research.assistant.entity.PaperAnnotation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface PaperAnnotationMapper extends BaseMapper<PaperAnnotation> {
    @Select("SELECT * FROM paper_annotation WHERE agent_tool_call_id = #{toolCallId} LIMIT 1")
    PaperAnnotation selectByAgentToolCallId(@Param("toolCallId") String toolCallId);
}
