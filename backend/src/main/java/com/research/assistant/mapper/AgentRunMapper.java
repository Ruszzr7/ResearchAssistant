package com.research.assistant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.research.assistant.entity.AgentRunRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface AgentRunMapper extends BaseMapper<AgentRunRecord> {

    @Select("SELECT * FROM agent_run WHERE run_id = #{runId} LIMIT 1")
    AgentRunRecord selectByRunId(@Param("runId") String runId);

    @Select("SELECT * FROM agent_run WHERE turn_id = #{turnId} ORDER BY attempt_no DESC LIMIT 1")
    AgentRunRecord selectLatestByTurn(@Param("turnId") long turnId);

    @Select("SELECT * FROM agent_run WHERE status = 'RUNNING'")
    List<AgentRunRecord> selectRunning();

    @Select("SELECT * FROM agent_run WHERE status = 'QUEUED'")
    List<AgentRunRecord> selectQueued();

    @Update("UPDATE agent_run SET status = #{target}, version = version + 1, "
            + "started_at = CASE WHEN #{target} = 'RUNNING' AND started_at IS NULL THEN CURRENT_TIMESTAMP(6) ELSE started_at END, "
            + "completed_at = CASE WHEN #{terminal} THEN CURRENT_TIMESTAMP(6) ELSE completed_at END, "
            + "result_json = #{resultJson}, error_code = #{errorCode}, error_message = #{errorMessage} "
            + "WHERE id = #{id} AND status = #{expected} AND version = #{version}")
    int transition(@Param("id") long id,
                   @Param("expected") String expected,
                   @Param("target") String target,
                   @Param("version") int version,
                   @Param("terminal") boolean terminal,
                   @Param("resultJson") String resultJson,
                   @Param("errorCode") String errorCode,
                   @Param("errorMessage") String errorMessage);

    @Update("UPDATE agent_run SET model_calls = #{modelCalls}, tool_calls = #{toolCalls}, "
            + "prompt_tokens = #{promptTokens}, completion_tokens = #{completionTokens} "
            + "WHERE run_id = #{runId}")
    int recordUsage(@Param("runId") String runId,
                    @Param("modelCalls") int modelCalls,
                    @Param("toolCalls") int toolCalls,
                    @Param("promptTokens") int promptTokens,
                    @Param("completionTokens") int completionTokens);

    @Update("UPDATE agent_run SET model_trace_json = #{modelTraceJson} WHERE run_id = #{runId}")
    int recordModelTrace(@Param("runId") String runId,
                         @Param("modelTraceJson") String modelTraceJson);
}
