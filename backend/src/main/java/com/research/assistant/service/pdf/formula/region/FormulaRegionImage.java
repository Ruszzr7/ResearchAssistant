package com.research.assistant.service.pdf.formula.region;

import java.util.Base64;

public record FormulaRegionImage(byte[] png, int width, int height) {

    String dataUrl() {
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(png);
    }
}
