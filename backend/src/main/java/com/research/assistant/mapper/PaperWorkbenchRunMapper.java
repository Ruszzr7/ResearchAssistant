package com.research.assistant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.research.assistant.entity.PaperWorkbenchRunRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.time.LocalDateTime;

@Mapper
public interface PaperWorkbenchRunMapper extends BaseMapper<PaperWorkbenchRunRecord> {

    String COLUMNS = "id, run_id, task_id, research_session_id, workflow, scope, status, paper_ids_json, request_json, "
            + "plan_json, artifact_versions_json, evidence_required, max_steps, token_budget, repair_count, "
            + "evidence_count, prompt_tokens, completion_tokens, total_tokens, latency_ms, result_json, "
            + "error_code, error_message, started_at, completed_at, created_at, updated_at";

    @Select("SELECT " + COLUMNS + " FROM paper_workbench_run WHERE run_id = #{runId} LIMIT 1")
    PaperWorkbenchRunRecord selectByRunId(@Param("runId") String runId);

    @Select("SELECT " + COLUMNS + " FROM paper_workbench_run ORDER BY created_at DESC, id DESC LIMIT #{limit}")
    List<PaperWorkbenchRunRecord> selectRecent(@Param("limit") int limit);

    @Select("SELECT " + COLUMNS + " FROM paper_workbench_run "
            + "WHERE status IN ('PLANNED','QUEUED','RUNNING') AND task_id IS NOT NULL "
            + "ORDER BY updated_at ASC, id ASC LIMIT #{limit}")
    List<PaperWorkbenchRunRecord> selectActive(@Param("limit") int limit);

    @Select("SELECT " + COLUMNS + " FROM paper_workbench_run "
            + "WHERE research_session_id = #{sessionId} ORDER BY created_at DESC, id DESC LIMIT #{limit}")
    List<PaperWorkbenchRunRecord> selectByResearchSessionId(@Param("sessionId") long sessionId,
                                                             @Param("limit") int limit);

    @Select("SELECT " + COLUMNS + " FROM paper_workbench_run WHERE research_session_id IS NULL "
            + "ORDER BY created_at DESC, id DESC LIMIT #{limit}")
    List<PaperWorkbenchRunRecord> selectUnlinked(@Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM paper_workbench_run WHERE research_session_id = #{sessionId}")
    long countByResearchSessionId(@Param("sessionId") long sessionId);

    /** Bounded, newest-first window for restart-stable aggregate metrics. */
    @Select("SELECT " + COLUMNS + " FROM paper_workbench_run "
            + "WHERE created_at >= #{since} ORDER BY created_at DESC, id DESC LIMIT #{limit}")
    List<PaperWorkbenchRunRecord> selectForMetrics(@Param("since") LocalDateTime since,
                                                   @Param("limit") int limit);
}
