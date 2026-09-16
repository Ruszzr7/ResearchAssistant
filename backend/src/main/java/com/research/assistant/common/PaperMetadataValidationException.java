package com.research.assistant.common;

/** User-correctable paper metadata validation failure. */
public class PaperMetadataValidationException extends RuntimeException {

    private final String field;

    public PaperMetadataValidationException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String getField() {
        return field;
    }
}
