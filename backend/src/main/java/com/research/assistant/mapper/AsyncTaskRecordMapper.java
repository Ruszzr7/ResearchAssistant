package com.research.assistant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.research.assistant.entity.AsyncTaskRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 异步任务记录 Mapper。
 */
@Mapper
public interface AsyncTaskRecordMapper extends BaseMapper<AsyncTaskRecord> {

    /**
     * 按任务 ID 查询记录。
     */
    @Select("SELECT id, task_id, status, workflow_type, context_json, title, stage_text, " +
            "result_json, error, created_at, updated_at FROM async_task WHERE task_id = #{taskId}")
    AsyncTaskRecord selectByTaskId(@Param("taskId") String taskId);

    /**
     * 查询最近的任务记录。
     */
    @Select("SELECT id, task_id, status, workflow_type, context_json, title, stage_text, " +
            "result_json, error, created_at, updated_at FROM async_task ORDER BY created_at DESC LIMIT #{limit}")
    List<AsyncTaskRecord> selectRecent(@Param("limit") int limit);

    /**
     * 按状态统计任务数。
     */
    @Select("SELECT COUNT(*) FROM async_task WHERE status = #{status}")
    long countByStatus(@Param("status") String status);
}
