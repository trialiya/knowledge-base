package io.github.trialiya.kb.model.chat.dto;

import io.github.trialiya.kb.model.chat.entity.ScriptEventMeta;
import java.time.LocalDateTime;

/**
 * Нагрузка события {@link ChatEventType#SCRIPT_RUN}: ряд, который запуск сохранённого скрипта
 * оставил в истории чата.
 *
 * <p>Та же форма, что у {@link GitCommandPayload}, и по той же причине: фронт собирает из неё ряд
 * сам, а {@link ChatMessage} целиком сюда не едет — контент у такого ряда пустой.
 *
 * @param id id сохранённого ряда — якорь, по которому вкладка отбрасывает собственное эхо
 */
public record ScriptRunPayload(long id, LocalDateTime createdAt, ScriptEventMeta event) {}
