package io.github.trialiya.kb.model.chat.dto;

import io.github.trialiya.kb.tools.ToolInvocationMeta;
import java.util.List;

public record ToolCallsMessage(List<ToolInvocationMeta> toolCalls) {}
