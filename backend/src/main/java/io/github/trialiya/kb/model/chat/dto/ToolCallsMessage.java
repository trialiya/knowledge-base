package io.github.trialiya.kb.model.chat.dto;

import io.github.trialiya.kb.tools.ToolInvocationCollector;
import java.util.List;

public record ToolCallsMessage(List<ToolInvocationCollector.ToolInvocation> toolCalls) {}
