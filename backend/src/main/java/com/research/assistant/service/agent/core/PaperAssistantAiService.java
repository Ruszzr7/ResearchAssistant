package com.research.assistant.service.agent.core;

import dev.langchain4j.service.Result;
import dev.langchain4j.service.UserMessage;

/** Single-agent entry point; LangChain4j owns the native tool-calling loop. */
interface PaperAssistantAiService {

    @UserMessage("{{it}}")
    Result<String> chat(String message);
}
