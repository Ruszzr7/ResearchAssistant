package com.research.assistant.service.ai.skill;

/**
 * 技能接口 —— 所有 AI 原子能力的统一抽象。
 *
 * @param <I> 输入类型
 * @param <O> 输出类型
 */
public interface Skill<I, O> {

    /**
     * 技能唯一标识。
     */
    String name();

    /**
     * 技能能力描述，供 Planner / 用户理解该 Skill 能做什么。
     */
    String description();

    /**
     * 输入类型，用于把 Planner 生成的 JSON 参数反序列化为输入对象。
     */
    Class<I> inputType();

    /**
     * 执行技能。
     *
     * @param ctx   执行上下文
     * @param input 输入
     * @return 输出
     * @throws Exception 执行过程中抛出的异常
     */
    O execute(SkillContext ctx, I input) throws Exception;
}
