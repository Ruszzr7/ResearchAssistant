package com.research.assistant.entity;

import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDateTime;

/**
 * 系统设置实体 —— Key-Value 形式存储用户配置（如 API Key、模型名）。
 * <p>
 * Key 命名规范: api_key / model / base_url。
 *
 * @author ResearchAssistant
 */
@TableName("settings")
public class Settings {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("key_name")
    private String keyName;

    private String value;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    public Settings() {}

    public Settings(String keyName, String value) {
        this.keyName = keyName;
        this.value = value;
    }

    // ========== getter / setter ==========

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getKeyName() { return keyName; }
    public void setKeyName(String keyName) { this.keyName = keyName; }
    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
