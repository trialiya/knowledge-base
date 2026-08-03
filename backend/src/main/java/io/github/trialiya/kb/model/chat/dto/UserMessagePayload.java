package io.github.trialiya.kb.model.chat.dto;

import java.time.LocalDateTime;
import org.jspecify.annotations.Nullable;

/**
 * Нагрузка события {@link ChatEventType#USER_MESSAGE}.
 *
 * @param id id сохранённого сообщения — якорь для поиска по чату на фронте. Сообщение пишется до
 *     старта прогона ({@code ChatMemoryService.saveUserMessage}), поэтому id известен уже здесь;
 *     {@code null} — только у событий, отреплеенных из прогонов, записанных до этого изменения.
 */
public record UserMessagePayload(@Nullable Long id, String text, LocalDateTime createdAt) {}
