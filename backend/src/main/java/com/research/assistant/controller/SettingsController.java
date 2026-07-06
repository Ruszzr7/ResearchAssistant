package com.research.assistant.controller;

import com.research.assistant.common.Result;
import com.research.assistant.entity.Settings;
import com.research.assistant.service.SettingsService;
import org.springframework.web.bind.annotation.*;

import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 系统设置 REST 接口。
 * <p>
 * 前端在 SettingsView 中读取/保存设置，测试连接单独一个接口。
 * 返回的 settings 列表中 API Key 已做脱敏处理，避免在接口中暴露完整密钥。
 */
@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final SettingsService settingsService;

    public SettingsController(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    /** GET /api/settings — 获取所有设置（api_key 脱敏返回） */
    @GetMapping
    public Result<List<Settings>> getAll() {
        List<Settings> list = settingsService.getAll();
        for (Settings s : list) {
            if ("api_key".equals(s.getKeyName()) && StringUtils.hasLength(s.getValue())) {
                s.setValue(maskApiKey(s.getValue()));
            }
        }
        return Result.ok(list);
    }

    private String maskApiKey(String value) {
        if (value == null) {
            return "";
        }
        if (value.length() <= 8) {
            return "****";
        }
        int prefixLen = Math.min(6, value.length() - 4);
        return value.substring(0, prefixLen) + "****" + value.substring(value.length() - 4);
    }

    /** PUT /api/settings — 批量保存设置 */
    @PutMapping
    public Result<Void> saveAll(@RequestBody List<Settings> settings) {
        settingsService.saveAll(settings);
        return Result.ok();
    }

    /** POST /api/settings/test — 测试 LLM API 连接 */
    @PostMapping("/test")
    public Result<Map<String, Object>> testConnection() {
        try {
            boolean ok = settingsService.testConnection();
            Map<String, Object> data = new HashMap<>();
            data.put("success", ok);
            data.put("message", ok ? "连接成功" : "连接失败，请检查 API Key、模型名和 Base URL");
            return Result.ok(data);
        } catch (Exception e) {
            Map<String, Object> data = new HashMap<>();
            data.put("success", false);
            data.put("message", "连接失败: " + e.getMessage());
            return Result.ok(data);
        }
    }
}
