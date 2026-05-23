package io.github.trialiya.kb.model.chat.dto;

import io.github.trialiya.kb.tools.ToolInvocationCollector;

public record ToolCallMessage(ToolInvocationCollector.ToolInvocation toolCall) {}
