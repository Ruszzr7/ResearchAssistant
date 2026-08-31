package com.research.assistant.dto.agent;

public record AgentAttachmentView(String attachmentId, String name, String mediaType,
                                  long sizeBytes, String extractionStatus) { }
