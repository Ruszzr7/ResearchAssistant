package com.research.assistant.service.memory;

import com.research.assistant.dto.LlmResponse;
import com.research.assistant.service.ai.LlmCallPolicy;
import dev.langchain4j.data.message.Content;

import java.util.List;

/** Sends the single multimodal user message used by paper understanding. */
public interface PaperMemoryModelClient {
    LlmResponse chat(String systemPrompt, List<Content> userContents, LlmCallPolicy policy);
}
