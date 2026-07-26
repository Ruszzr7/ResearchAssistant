package com.research.assistant.controller;

import com.research.assistant.common.Result;
import com.research.assistant.dto.AiConnectionTestResult;
import com.research.assistant.entity.Settings;
import com.research.assistant.service.SettingsService;
import com.research.assistant.service.security.SettingsPolicy;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Settings API. Secrets are always returned as masked values. */
@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private static final Logger log = LoggerFactory.getLogger(SettingsController.class);
    private final SettingsService settingsService;

    public SettingsController(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @GetMapping
    public Result<List<Settings>> getAll() {
        List<Settings> list = settingsService.getAll();
        // Defense in depth for alternate SettingsService implementations.
        for (Settings setting : list) {
            if (SettingsPolicy.isSensitive(setting.getKeyName())) {
                setting.setConfigured(setting.getValue() != null && !setting.getValue().isBlank());
                if (!SettingsPolicy.isMaskedValue(setting.getValue())) {
                    setting.setValue(SettingsPolicy.mask(setting.getValue()));
                }
            }
        }
        return Result.ok(list);
    }

    @PutMapping
    public Result<Void> saveAll(@RequestBody List<@Valid Settings> settings) {
        settingsService.saveAll(settings);
        return Result.ok();
    }

    @PostMapping("/test")
    public ResponseEntity<Result<AiConnectionTestResult>> testConnection() {
        try {
            return ResponseEntity.ok(Result.ok(settingsService.testConnection()));
        } catch (Exception e) {
            log.warn("LLM connection test failed type={}", e.getClass().getSimpleName());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Result.error(502, "上游模型服务暂时不可用，请检查配置后重试"));
        }
    }
}
