package com.research.assistant.service.pdf.layout;

import java.util.List;

/** Persisted, locally derived math profile for one canonical text block. */
public record MathContentProfile(MathContentLevel level,
                                 double density,
                                 int signalCount,
                                 List<InlineMathFragment> fragments,
                                 String detectorVersion) {

    public MathContentProfile {
        level = level == null ? MathContentLevel.NONE : level;
        density = Math.max(0, Math.min(1, density));
        signalCount = Math.max(0, signalCount);
        fragments = fragments == null ? List.of() : List.copyOf(fragments);
        detectorVersion = detectorVersion == null ? "" : detectorVersion;
    }

    public static MathContentProfile none(String detectorVersion) {
        return new MathContentProfile(MathContentLevel.NONE, 0, 0, List.of(), detectorVersion);
    }
}
