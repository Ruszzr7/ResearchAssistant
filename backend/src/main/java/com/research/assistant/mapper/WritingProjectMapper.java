package com.research.assistant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.research.assistant.entity.WritingProject;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * WritingProject Mapper。
 */
@Mapper
public interface WritingProjectMapper extends BaseMapper<WritingProject> {

    @Update("UPDATE writing_project SET updated_at = CURRENT_TIMESTAMP WHERE id = #{projectId}")
    int touch(@Param("projectId") long projectId);
}
