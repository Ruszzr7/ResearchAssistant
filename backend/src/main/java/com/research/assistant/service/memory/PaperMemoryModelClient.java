package com.research.assistant.service.memory;

import com.research.assistant.dto.LlmResponse;
import com.research.assistant.service.ai.LlmCallPolicy;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.TextContent;

import java.util.List;

public interface PaperMemoryModelClient {
    LlmResponse chat(String systemPrompt, String userMessage, LlmCallPolicy policy);

    /**
     * Sends one user message that may contain text, a native PDF, and/or page
     * images. Text-only test clients keep working through the compatibility
     * default; the production document client overrides this method.
     */
    default LlmResponse chat(String systemPrompt, List<Content> userContents,
                             LlmCallPolicy policy) {
        StringBuilder flattened = new StringBuilder();
        if (userContents != null) {
            for (Content content : userContents) {
                if (content instanceof TextContent text) {
                    if (flattened.length() > 0) flattened.append('\n');
                    flattened.append(text.text());
                } else {
                    if (flattened.length() > 0) flattened.append('\n');
                    flattened.append("[multimodal document content]");
                }
            }
        }
        return chat(systemPrompt, flattened.toString(), policy);
    }
}
