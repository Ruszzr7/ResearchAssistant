package com.research.assistant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.research.assistant.entity.PaperWorkbenchRunRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface PaperWorkbenchRunMapper extends BaseMapper<PaperWorkbenchRunRecord> {

    String COLUMNS = "id, run_id, task_id, workflow, scope, status, paper_ids_json, request_json, "
            + "plan_json, artifact_versions_json, evidence_required, max_steps, token_budget, repair_count, "
            + "evidence_count, prompt_tokens, completion_tokens, total_tokens, latency_ms, result_json, "
            + "error_code, error_message, started_at, completed_at, created_at, updated_at";

    @Select("SELECT " + COLUMNS + " FROM paper_workbench_run WHERE run_id = #{runId} LIMIT 1")
    PaperWorkbenchRunRecord selectByRunId(@Param("runId") String runId);
}
