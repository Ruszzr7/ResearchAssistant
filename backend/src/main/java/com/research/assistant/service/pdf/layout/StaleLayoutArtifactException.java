package com.research.assistant.service.pdf.layout;

/** Raised when a client anchor refers to a replaced PDF or obsolete parser. */
public class StaleLayoutArtifactException extends RuntimeException {
    public StaleLayoutArtifactException() {
        super("PDF layout artifact changed");
    }
}
