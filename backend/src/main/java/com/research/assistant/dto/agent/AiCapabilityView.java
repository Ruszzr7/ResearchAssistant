package com.research.assistant.dto.agent;

import java.time.LocalDateTime;

public record AiCapabilityView(String status, boolean chat, boolean toolCalling,
                              boolean continuousTools, boolean toolImageContinuation,
                              boolean structured, boolean image, boolean pdf,
                              String errorCode, String message, LocalDateTime verifiedAt, LocalDateTime expiresAt) { }
