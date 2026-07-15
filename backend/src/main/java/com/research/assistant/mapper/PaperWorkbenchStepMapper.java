package com.research.assistant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.research.assistant.entity.PaperWorkbenchStepRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface PaperWorkbenchStepMapper extends BaseMapper<PaperWorkbenchStepRecord> {

    String COLUMNS = "id, run_id, step_index, step_name, skill_name, step_kind, status, evidence_count, "
            + "retry_count, prompt_tokens, completion_tokens, total_tokens, latency_ms, input_summary_json, "
            + "output_summary_json, error_code, error_message, started_at, completed_at, created_at, updated_at";

    @Select("SELECT " + COLUMNS + " FROM paper_workbench_step WHERE run_id = #{runId} ORDER BY step_index")
    List<PaperWorkbenchStepRecord> findByRunId(@Param("runId") String runId);

    @Select("SELECT " + COLUMNS + " FROM paper_workbench_step "
            + "WHERE run_id = #{runId} AND step_index = #{stepIndex} LIMIT 1")
    PaperWorkbenchStepRecord selectByRunAndIndex(@Param("runId") String runId,
                                                 @Param("stepIndex") int stepIndex);
}
