package com.research.assistant.service.pdf.layout;

/** Safe result envelope; raw process output and local paths never enter traces. */
public record ExternalLayoutParseResult(boolean success,
                                        PaperLayoutArtifact artifact,
                                        String parser,
                                        String errorCode) {

    public static ExternalLayoutParseResult success(PaperLayoutArtifact artifact, String parser) {
        return new ExternalLayoutParseResult(true, artifact, parser, "");
    }

    public static ExternalLayoutParseResult failure(String parser, String errorCode) {
        return new ExternalLayoutParseResult(false, null,
                parser == null ? "external" : parser,
                errorCode == null || errorCode.isBlank() ? "EXTERNAL_LAYOUT_FAILED" : errorCode);
    }
}
