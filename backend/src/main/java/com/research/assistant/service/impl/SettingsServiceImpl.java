package com.research.assistant.service.impl;

import com.research.assistant.entity.Settings;
import com.research.assistant.mapper.SettingsMapper;
import com.research.assistant.service.LLMService;
import com.research.assistant.service.SettingsService;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Settings 服务实现。
 * <p>
 * saveAll 使用 saveOrUpdate 逐条处理（数据量极小，无需批量优化）。
 */
@Service
public class SettingsServiceImpl implements SettingsService {

    private final SettingsMapper settingsMapper;
    private final LLMService llmService;

    /** @Lazy 打破与 LLMServiceImpl 之间的循环依赖 */
    public SettingsServiceImpl(SettingsMapper settingsMapper, @Lazy LLMService llmService) {
        this.settingsMapper = settingsMapper;
        this.llmService = llmService;
    }

    @Override
    public List<Settings> getAll() {
        return settingsMapper.selectList(null);
    }

    @Override
    public String getValue(String keyName) {
        Settings s = settingsMapper.selectByKey(keyName);
        return s != null ? s.getValue() : null;
    }

    @Override
    @Transactional
    public void saveAll(List<Settings> settings) {
        for (Settings s : settings) {
            Settings existing = settingsMapper.selectByKey(s.getKeyName());
            if (existing != null) {
                s.setId(existing.getId());
                settingsMapper.updateById(s);
            } else {
                settingsMapper.insert(s);
            }
        }
    }

    @Override
    public boolean testConnection() {
        String result = llmService.chat("Reply with exactly one word: OK", "ping");
        return result != null && !result.isEmpty();
    }
}
