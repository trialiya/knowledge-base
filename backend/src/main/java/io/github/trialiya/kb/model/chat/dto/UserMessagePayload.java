package io.github.trialiya.kb.model.chat.dto;

import io.github.trialiya.kb.model.chat.entity.ContextItem;
import java.time.LocalDateTime;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Нагрузка события {@link ChatEventType#USER_MESSAGE}.
 *
 * @param id id сохранённого сообщения — якорь для поиска по чату на фронте. Сообщение пишется до
 *     старта прогона ({@code ChatMemoryService.saveUserMessage}), поэтому id известен уже здесь;
 *     {@code null} — только у событий, отреплеенных из прогонов, записанных до этого изменения.
 * @param contextItems что приложено к вопросу — чтобы чипы вложений появились и в других вкладках,
 *     не дожидаясь перезагрузки
 */
public record UserMessagePayload(
        @Nullable Long id, String text, LocalDateTime createdAt, List<ContextItem> contextItems) {}
