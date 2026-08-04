package io.github.trialiya.kb.model.chat.dto;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Тело {@code POST /runs}: вопрос пользователя и приложенный к нему контекст.
 *
 * @param text текст вопроса; при повторе упавшего прогона ({@code ?retry=true}) не нужен — ходом
 *     остаётся уже сохранённый вопрос
 * @param contextItems что приложено к этому сообщению (вложения); проверяется и дополняется
 *     подписями на бэке
 */
public record StartRunRequest(
        @Nullable String text, @Nullable List<ContextItemRequest> contextItems) {}
