package com.research.assistant.service.agent.document;

public record DocumentInputRequest(boolean wholeDocument, boolean visualQuestion,
                                   int structuredCharacters, int requestedPages) { }
