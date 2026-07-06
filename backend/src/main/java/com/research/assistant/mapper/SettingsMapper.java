package com.research.assistant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.research.assistant.entity.Settings;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * Settings Mapper —— key-value 查询。
 */
@Mapper
public interface SettingsMapper extends BaseMapper<Settings> {

    /** 按 key 查询单条设置 */
    @Select("SELECT * FROM settings WHERE key_name = #{keyName}")
    Settings selectByKey(@Param("keyName") String keyName);
}
