package com.research.assistant.service.ai.skill.io;

import java.util.List;
import java.util.Map;

/**
 * 批量导入选中论文 Skill 输入。
 */
public record ImportSelectedPapersInput(List<Map<String, Object>> selected, Long folderId) {
}
