package io.github.trialiya.kb.model.chat.dto;

import io.github.trialiya.kb.tools.ToolInvocation;
import java.util.List;

public record ToolCallsMessage(List<ToolInvocation> toolCalls) {}
