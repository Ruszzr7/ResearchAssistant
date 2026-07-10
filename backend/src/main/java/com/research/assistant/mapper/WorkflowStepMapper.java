package com.research.assistant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.research.assistant.entity.WorkflowStepRecord;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 工作流步骤 Mapper。
 */
@Mapper
public interface WorkflowStepMapper extends BaseMapper<WorkflowStepRecord> {

    @Select("SELECT * FROM workflow_step WHERE task_id = #{taskId} ORDER BY step_index")
    List<WorkflowStepRecord> findByTaskId(@Param("taskId") String taskId);

    @Delete("DELETE FROM workflow_step WHERE task_id = #{taskId}")
    int deleteByTaskId(@Param("taskId") String taskId);
}
