package com.research.assistant.service.metadata;

/** Local metadata candidates read from PDF document information and page geometry. */
public record PdfDocumentMetadata(String title,
                                  String authors,
                                  String abstractText,
                                  String subject,
                                  String keywords) {

    public static PdfDocumentMetadata empty() {
        return new PdfDocumentMetadata(null, null, null, null, null);
    }
}
