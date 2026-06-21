package io.github.trialiya.kb.model.chat.entity;

import io.github.trialiya.kb.model.tool.ToolInvocationMeta;
import jakarta.annotation.Nullable;
import java.util.List;

public record ChatMessageMeta(@Nullable String runId, List<ToolInvocationMeta> invocations) {

    public ChatMessageMeta {
        invocations = invocations == null ? List.of() : invocations;
    }

    public ChatMessageMeta(List<ToolInvocationMeta> invocations) {
        this(null, invocations);
    }
}
