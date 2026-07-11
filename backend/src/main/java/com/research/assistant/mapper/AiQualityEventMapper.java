package com.research.assistant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.research.assistant.dto.AiQualityStatusCount;
import com.research.assistant.entity.AiQualityEvent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface AiQualityEventMapper extends BaseMapper<AiQualityEvent> {

    @Select("SELECT id, paper_id, task_type, stage, prompt_version, model_name, status, repaired, "
            + "retry_count, validation_errors_json, error_message, prompt_tokens, completion_tokens, "
            + "total_tokens, latency_ms, created_at FROM ai_quality_event "
            + "ORDER BY created_at DESC LIMIT #{limit}")
    List<AiQualityEvent> selectRecent(@Param("limit") int limit);

    @Select("SELECT status, COUNT(*) AS event_count, AVG(latency_ms) AS average_latency_ms, "
            + "COALESCE(SUM(total_tokens), 0) AS total_tokens FROM ai_quality_event "
            + "WHERE created_at >= #{from} GROUP BY status ORDER BY status")
    List<AiQualityStatusCount> summarizeSince(@Param("from") LocalDateTime from);
}
