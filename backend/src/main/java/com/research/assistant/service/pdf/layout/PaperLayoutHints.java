package com.research.assistant.service.pdf.layout;

/** Optional library metadata used to make first-page role detection safer. */
public record PaperLayoutHints(String title, String authors, String abstractText) {

    public static PaperLayoutHints empty() {
        return new PaperLayoutHints("", "", "");
    }

    public PaperLayoutHints {
        title = title == null ? "" : title;
        authors = authors == null ? "" : authors;
        abstractText = abstractText == null ? "" : abstractText;
    }
}
