package io.github.trialiya.kb.model.chat.dto;

import io.github.trialiya.kb.model.chat.entity.CompactMeta;
import io.github.trialiya.kb.model.chat.entity.ContextItem;
import io.github.trialiya.kb.model.chat.entity.GitEventMeta;
import io.github.trialiya.kb.model.chat.entity.RunTokenUsage;
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
        @Nullable CompactMeta compact,
        /**
         * Git-команда, выполненная пользователем из этого чата; непустой ровно у ряда этой команды
         * — по нему фронт и рисует карточку вывода вместо обычного пузыря.
         */
        @Nullable GitEventMeta gitEvent,
        /**
         * Вопрос был задан во время прогона, а не между ходами; {@code null} у всех остальных.
         * Фронту он нужен по той же причине, что и бэкенду: такой ряд не открывает ход, и всё, что
         * ищет «последний вопрос» в ленте, обязано смотреть сквозь него (см. {@code
         * trimActiveRunTail}).
         */
        @Nullable Boolean interjection,
        /**
         * Токены прогона, написавшего этот ответ; непустой ровно у одного его ряда — последнего
         * (см. {@code ChatHistoryService.markRunResult}). Тем же полем чат отвечает на вопрос
         * «сколько занято контекста»: у самого свежего ответа оно и есть текущее заполнение.
         */
        @Nullable RunTokenUsage usage) {}
