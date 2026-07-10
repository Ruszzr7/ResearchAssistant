package com.research.assistant.service.ai.skill.io;

import java.util.List;

/**
 * 论文对比 Skill 输入。
 */
public record ComparePapersInput(List<Long> paperIds, String customDimensions) {
}
