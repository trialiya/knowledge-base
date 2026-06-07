package io.github.trialiya.kb.model.chat.entity;

import io.github.trialiya.kb.tools.ToolInvocationMeta;
import java.util.List;

public record ChatMessageMeta(List<ToolInvocationMeta> invocations) {

    public ChatMessageMeta {
        invocations = invocations == null ? List.of() : invocations;
    }
}
