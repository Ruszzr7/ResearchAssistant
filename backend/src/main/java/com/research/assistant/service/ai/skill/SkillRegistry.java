package com.research.assistant.service.ai.skill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 技能注册表 —— 启动时自动收集所有 {@link Skill} Spring Bean。
 */
@Component
public class SkillRegistry {

    private static final Logger log = LoggerFactory.getLogger(SkillRegistry.class);

    private final Map<String, Skill<?, ?>> skills;

    public SkillRegistry(List<Skill<?, ?>> skillList) {
        this.skills = skillList.stream()
                .collect(Collectors.toMap(Skill::name, s -> s, (a, b) -> {
                    log.warn("技能名称冲突: {}，保留第一个", a.name());
                    return a;
                }));
        log.info("已注册 {} 个 Skill: {}", skills.size(), skills.keySet());
    }

    /**
     * 按名称获取技能。
     */
    public Skill<?, ?> get(String name) {
        return skills.get(name);
    }

    /**
     * 获取所有已注册技能。
     */
    public Collection<Skill<?, ?>> all() {
        return skills.values();
    }

    /**
     * 是否包含指定技能。
     */
    public boolean contains(String name) {
        return skills.containsKey(name);
    }
}
