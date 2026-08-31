package io.github.trialiya.kb.model.chat.dto;

import io.github.trialiya.kb.model.chat.entity.FileRevertMeta;
import java.time.LocalDateTime;

/**
 * Нагрузка события {@link ChatEventType#FILE_REVERT} и ответ эндпоинта отката: ряд, который откат
 * оставил в истории чата.
 *
 * <p>Форма — та же, что у {@link GitCommandPayload}, и по той же причине: фронт собирает сообщение
 * сам, а у ряда, кроме id, времени и самого события, ничего нет.
 *
 * @param id id сохранённого ряда — тот же якорь, что у {@link UserMessagePayload}
 */
public record FileRevertPayload(long id, LocalDateTime createdAt, FileRevertMeta event) {}
