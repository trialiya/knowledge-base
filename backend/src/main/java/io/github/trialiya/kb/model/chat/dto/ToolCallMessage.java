package io.github.trialiya.kb.model.chat.dto;

import io.github.trialiya.kb.model.tool.ToolInvocationMeta;

/**
 * Live-событие одного вызова инструмента. Несёт ту же мету, что и финальный {@link
 * ToolCallsMessage} (resultMeta, hasDetails, resultGist): фронт показывает блоки «изменения
 * документов/файлов» и модалку деталей по ходу прогона, не дожидаясь его завершения.
 */
public record ToolCallMessage(ToolInvocationMeta toolCall) {}
