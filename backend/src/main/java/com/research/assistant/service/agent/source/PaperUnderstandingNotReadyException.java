package com.research.assistant.service.agent.source;

public class PaperUnderstandingNotReadyException extends RuntimeException {
    public PaperUnderstandingNotReadyException(long paperId) {
        super("paper understanding is not ready: " + paperId);
    }
}
