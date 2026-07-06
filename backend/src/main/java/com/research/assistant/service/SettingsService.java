package com.research.assistant.service;

import com.research.assistant.entity.Settings;
import java.util.List;

/**
 * 系统设置服务 —— 管理用户自配的 API Key、模型等。
 */
public interface SettingsService {

    /** 获取所有设置 */
    List<Settings> getAll();

    /** 按 key 获取单个值，不存在返回 null */
    String getValue(String keyName);

    /** 批量保存（新增或更新） */
    void saveAll(List<Settings> settings);

    /** 测试 DeepSeek API 连接是否正常 */
    boolean testConnection();
}
