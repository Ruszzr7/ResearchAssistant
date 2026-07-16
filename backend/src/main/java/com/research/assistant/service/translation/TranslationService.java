package com.research.assistant.service.translation;

import com.research.assistant.dto.translation.TranslationRequest;
import com.research.assistant.dto.translation.TranslationResponse;
import com.research.assistant.dto.translation.TranslationStatus;

public interface TranslationService {
    TranslationStatus status();

    TranslationResponse translate(TranslationRequest request);
}
