package com.research.assistant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.research.assistant.entity.AiModelCapabilityRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AiModelCapabilityMapper extends BaseMapper<AiModelCapabilityRecord> {
    @Select("SELECT * FROM ai_model_capability WHERE model_role = 'UNIFIED' AND config_signature = #{signature} LIMIT 1")
    AiModelCapabilityRecord selectVersion(@Param("signature") String signature);
}
