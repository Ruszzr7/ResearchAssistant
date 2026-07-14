package com.research.assistant.common;

/** Indicates that an imported PDF/DOI already belongs to a paper in the library. */
public class DuplicatePaperException extends RuntimeException {

    private final Long existingPaperId;
    private final String existingTitle;

    public DuplicatePaperException(Long existingPaperId, String existingTitle) {
        super("文献已存在");
        this.existingPaperId = existingPaperId;
        this.existingTitle = existingTitle;
    }

    public Long getExistingPaperId() { return existingPaperId; }

    public String getExistingTitle() { return existingTitle; }
}
