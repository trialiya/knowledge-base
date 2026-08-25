package io.github.trialiya.kb.model.chat.dto;

import io.github.trialiya.kb.model.chat.entity.CompactMeta;
import io.github.trialiya.kb.model.chat.entity.ContextItem;
import io.github.trialiya.kb.model.tool.ToolInvocationMeta;
import java.time.LocalDateTime;
import java.util.List;
import org.jspecify.annotations.Nullable;

public record ChatMessage(
        @Nullable Long id,
        String content,
        String type,
        LocalDateTime timestamp,
        @Nullable List<ToolInvocationMeta> toolInvocationMetas,
        @Nullable String runId,
        boolean toolCalls,
        List<ContextItem> contextItems,
        @Nullable String project,
        @Nullable String projectSwitchFrom,
        /** Модель, написавшая ответ; {@code null} — ответы старее этого поля и вопросы. */
        @Nullable String model,
        /**
         * Итог сжатия {@code /compact}; непустой ровно у строки-плашки, которую сжатие оставило в
         * истории вместо себя, — по нему фронт и опознаёт её среди обычных сообщений.
         */
        @Nullable CompactMeta compact) {}
