package io.github.trialiya.kb.model.chat.dto;

import io.github.trialiya.kb.model.chat.entity.GitEventMeta;
import java.time.LocalDateTime;

/**
 * Нагрузка события {@link ChatEventType#GIT_COMMAND}: ряд, который git-команда оставила в истории
 * чата.
 *
 * <p>Ровно то, из чего фронт собирает сообщение сам, — id, время и само событие. Целиком {@link
 * ChatMessage} сюда не едет: у этого ряда пустой контент и ни одного поля из остальных двенадцати,
 * и отправлять их пустыми значило бы обещать, что когда-нибудь они здесь бывают непустыми.
 *
 * @param id id сохранённого ряда — тот же якорь, что у {@link UserMessagePayload}
 */
public record GitCommandPayload(long id, LocalDateTime createdAt, GitEventMeta event) {}
