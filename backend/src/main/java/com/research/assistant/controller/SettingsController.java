package com.research.assistant.controller;

import com.research.assistant.common.Result;
import com.research.assistant.dto.AiConnectionTestResult;
import com.research.assistant.dto.AiModelListRequest;
import com.research.assistant.dto.AiModelListResult;
import com.research.assistant.dto.AiCapabilityTestRequest;
import com.research.assistant.entity.Settings;
import com.research.assistant.service.AiModelCatalogService;
import com.research.assistant.service.SettingsService;
import com.research.assistant.service.security.SettingsPolicy;
import com.research.assistant.service.agent.capability.AiCapabilityService;
import com.research.assistant.dto.agent.AiCapabilityView;
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
    private final AiModelCatalogService modelCatalogService;
    private final AiCapabilityService capabilityService;

    @org.springframework.beans.factory.annotation.Autowired
    public SettingsController(SettingsService settingsService,
                              AiModelCatalogService modelCatalogService,
                              AiCapabilityService capabilityService) {
        this.settingsService = settingsService;
        this.modelCatalogService = modelCatalogService;
        this.capabilityService = capabilityService;
    }

    public SettingsController(SettingsService settingsService,
                              AiModelCatalogService modelCatalogService) {
        this(settingsService, modelCatalogService, null);
    }

    @PostMapping("/capabilities/test")
    public Result<AiCapabilityView> testCapabilities(
            @Valid @RequestBody(required = false) AiCapabilityTestRequest request) {
        if (request == null) return Result.ok(capabilityService.probe());
        return Result.ok(capabilityService.probeDraft(request.baseUrl(), request.model(), request.apiKey()));
    }

    @GetMapping("/capabilities")
    public Result<AiCapabilityView> getCapabilities() {
        return Result.ok(capabilityService.current());
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

    @PostMapping("/models")
    public ResponseEntity<Result<AiModelListResult>> listModels(
            @Valid @RequestBody AiModelListRequest request) {
        try {
            return ResponseEntity.ok(Result.ok(modelCatalogService.list(request)));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Result.error(400, exception.getMessage()));
        } catch (AiModelCatalogService.ModelCatalogException exception) {
            log.warn("AI model catalog query failed upstreamStatus={} type={}",
                    exception.upstreamStatus(), exception.getClass().getSimpleName());
            String message = exception.upstreamStatus() == 401 || exception.upstreamStatus() == 403
                    ? "查询失败，请检查 API Key 和模型权限"
                    : "查询失败，请检查 Base URL、API Key 或稍后重试";
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Result.error(502, message));
        }
    }
}
