package io.github.trialiya.kb.model.chat.dto;

import io.github.trialiya.kb.model.tool.ToolInvocationMeta;
import java.util.List;

public record ToolCallsMessage(List<ToolInvocationMeta> toolCalls) {}
