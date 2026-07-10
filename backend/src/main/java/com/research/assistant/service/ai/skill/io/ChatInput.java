package com.research.assistant.service.ai.skill.io;

/**
 * 对话追问 Skill 输入。
 */
public record ChatInput(String conversationId, String context, String question) {
}
