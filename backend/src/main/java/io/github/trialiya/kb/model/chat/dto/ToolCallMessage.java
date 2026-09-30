package io.github.trialiya.kb.model.chat.dto;

import io.github.trialiya.kb.model.tool.ToolInvocationMeta;
import org.jspecify.annotations.Nullable;

/**
 * Live-событие одного вызова инструмента. Несёт ту же мету, что и финальный {@link
 * ToolCallsMessage} (resultMeta, hasDetails, resultGist): фронт показывает блоки «изменения
 * документов/файлов» и модалку деталей по ходу прогона, не дожидаясь его завершения.
 *
 * @param contextTokens контекст после обращения к модели, которое этот вызов запросило, — только у
 *     {@code STARTED}: событие уходит сразу после записи ряда с вызовами, и замер обращения к этому
 *     моменту закрыт. Тот же {@code contextTokens}, что {@code markRunResult} запишет ряду в мету,
 *     — живьём и после перезагрузки подсказка одна и та же
 */
public record ToolCallMessage(
        ToolInvocationMeta toolCall, @Nullable Long contextTokens) {}
