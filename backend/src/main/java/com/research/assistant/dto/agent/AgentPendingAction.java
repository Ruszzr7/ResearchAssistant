package com.research.assistant.dto.agent;

import com.research.assistant.service.agent.action.ActionTarget;
import com.research.assistant.service.agent.action.PaperActionType;

import java.time.Instant;

public record AgentPendingAction(String toolCallId, PaperActionType actionType, ActionTarget target,
                                 String content, String color, String ticket, Instant expiresAt) { }
