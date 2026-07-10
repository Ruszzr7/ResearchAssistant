package com.research.assistant.service.pdf;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 图表区域类型。
 */
public enum FigureRegionType {
    FIGURE,
    TABLE,
    UNKNOWN;

    @JsonCreator
    public static FigureRegionType from(String value) {
        if (value == null || value.isBlank()) {
            return FIGURE;
        }
        return switch (value.trim().toUpperCase()) {
            case "TABLE", "TBL" -> TABLE;
            case "FIGURE", "FIG", "IMAGE", "IMG" -> FIGURE;
            default -> FIGURE;
        };
    }

    @JsonValue
    public String value() {
        return name();
    }
}
