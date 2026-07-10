package com.research.assistant.service.ai.skill;

import java.util.function.Consumer;

/**
 * Skill 执行上下文。
 *
 * @param taskId   所属任务 ID（可为空）
 * @param setStage 阶段文案更新回调，供前端轮询展示
 * @param shared   跨 Skill 共享的临时上下文
 */
public record SkillContext(String taskId, Consumer<String> setStage, java.util.Map<String, Object> shared) {

    public SkillContext(String taskId) {
        this(taskId, s -> {}, new java.util.HashMap<>());
    }

    public SkillContext(String taskId, Consumer<String> setStage) {
        this(taskId, setStage, new java.util.HashMap<>());
    }

    /**
     * 更新阶段文案。
     */
    public void stage(String text) {
        if (setStage != null) {
            setStage.accept(text);
        }
    }
}
