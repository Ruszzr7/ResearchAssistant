package com.research.assistant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.research.assistant.entity.AsyncTaskRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 异步任务记录 Mapper。
 */
@Mapper
public interface AsyncTaskRecordMapper extends BaseMapper<AsyncTaskRecord> {

    String COLUMNS = "id, task_id, status, task_type, workflow_type, context_json, title, stage_text, "
            + "result_json, error, failure_code, attempt_count, max_attempts, next_run_at, lease_owner, "
            + "lease_until, last_heartbeat_at, idempotency_key, request_hash, created_at, updated_at";

    /**
     * 按任务 ID 查询记录。
     */
    @Select("SELECT " + COLUMNS + " FROM async_task WHERE task_id = #{taskId}")
    AsyncTaskRecord selectByTaskId(@Param("taskId") String taskId);

    /**
     * 查询最近的任务记录。
     */
    @Select("SELECT " + COLUMNS + " FROM async_task ORDER BY created_at DESC LIMIT #{limit}")
    List<AsyncTaskRecord> selectRecent(@Param("limit") int limit);

    /**
     * 按状态统计任务数。
     */
    @Select("SELECT COUNT(*) FROM async_task WHERE status = #{status}")
    long countByStatus(@Param("status") String status);

    @Select("SELECT " + COLUMNS + " FROM async_task WHERE idempotency_key = #{idempotencyKey} LIMIT 1")
    AsyncTaskRecord selectByIdempotencyKey(@Param("idempotencyKey") String idempotencyKey);

    @Select("SELECT " + COLUMNS + " FROM async_task "
            + "WHERE task_type IS NOT NULL AND status IN ('PENDING', 'RETRY_WAIT') "
            + "AND (next_run_at IS NULL OR next_run_at <= CURRENT_TIMESTAMP) "
            + "ORDER BY created_at ASC LIMIT #{limit}")
    List<AsyncTaskRecord> selectDispatchable(@Param("limit") int limit);

    @Update("UPDATE async_task SET status = 'PROCESSING', lease_owner = #{owner}, "
            + "lease_until = #{leaseUntil}, last_heartbeat_at = CURRENT_TIMESTAMP, "
            + "attempt_count = attempt_count + 1, updated_at = CURRENT_TIMESTAMP "
            + "WHERE task_id = #{taskId} AND task_type IS NOT NULL "
            + "AND status IN ('PENDING', 'RETRY_WAIT') "
            + "AND (next_run_at IS NULL OR next_run_at <= CURRENT_TIMESTAMP)")
    int claimForExecution(@Param("taskId") String taskId,
                          @Param("owner") String owner,
                          @Param("leaseUntil") java.time.LocalDateTime leaseUntil);

    @Update("UPDATE async_task SET status = 'RETRY_WAIT', next_run_at = CURRENT_TIMESTAMP, "
            + "lease_owner = NULL, lease_until = NULL, last_heartbeat_at = NULL, "
            + "error = '任务租约已过期，等待重新调度', failure_code = 'LEASE_EXPIRED', "
            + "updated_at = CURRENT_TIMESTAMP "
            + "WHERE task_type IS NOT NULL AND status = 'PROCESSING' "
            + "AND lease_until IS NOT NULL AND lease_until < CURRENT_TIMESTAMP")
    int recoverExpiredLeases();
}
