package com.research.assistant.service;

import com.research.assistant.entity.Settings;
import com.research.assistant.dto.AiConnectionTestResult;
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

    /** 测试当前模型连接并返回不含上游正文或凭据的能力摘要。 */
    AiConnectionTestResult testConnection();
}
