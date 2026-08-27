package io.github.trialiya.kb.model.chat.dto;

import io.github.trialiya.kb.model.chat.entity.ContextItem;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Нагрузка события {@link ChatEventType#MESSAGE_QUEUED}.
 *
 * @param id id строки очереди ({@code chat_pending_message}) — НЕ id сообщения истории: его у
 *     сообщения ещё нет, оно появится только при доставке (событие {@code USER_MESSAGE})
 * @param text текст сообщения — вкладки, не отправлявшие его, рисуют пузырь по нему
 * @param contextItems что приложено к сообщению — чипы на «ожидающем» пузыре
 */
public record QueuedMessagePayload(
        Long id, String text, LocalDateTime createdAt, List<ContextItem> contextItems) {}
