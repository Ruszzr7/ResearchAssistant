package com.research.assistant.service.ai.skill.io;

import java.util.List;
import java.util.Map;

/**
 * 调研确认准备 Skill 输入。
 */
public record PrepareSurveyConfirmationInput(List<Map<String, Object>> candidates) {
}
