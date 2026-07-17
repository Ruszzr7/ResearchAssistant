package com.research.assistant.dto.research;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record ResearchMessageAppendRequest(
        @NotEmpty @Size(max = 20) List<@Valid ResearchMessageInput> messages
) { }
