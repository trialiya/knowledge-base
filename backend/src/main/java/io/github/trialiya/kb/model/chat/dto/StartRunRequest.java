package io.github.trialiya.kb.model.chat.dto;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Тело запроса, отправляющего сообщение в чат: {@code POST /runs} и {@code POST
 * /runs/{runId}/messages}. Одно тело на оба, потому что отправляют они одно и то же — вопрос со
 * своим контекстом и выбором пользователя; расходятся они в том, что с ним делает бэк (запускает
 * прогон или ставит в очередь идущему), а не в том, что приезжает.
 *
 * <p>Весь запрос — в теле, включая выбор: параметры запроса делили бы одно сообщение между двумя
 * местами, а строка адреса ещё и логируется целиком, вместе с текстом вопроса.
 *
 * @param text текст вопроса; при {@link #retry} не нужен — ходом остаётся уже сохранённый вопрос
 * @param contextItems что приложено к этому сообщению (вложения); проверяется и дополняется
 *     подписями на бэке
 * @param model id модели поверх выбранной в чате; {@code null} — модель чата
 * @param mode id режима ассистента поверх выбранного в чате; {@code null} — режим чата
 * @param project id проекта поверх выбранного в чате; {@code null} — проект чата
 * @param clientMsgId идентификатор клиента — чтобы вкладка-отправитель не задвоила свой
 *     оптимистично показанный пузырь, получив его же эхом
 * @param retry повтор упавшего прогона: нового сообщения не появляется, ходом остаётся последний
 *     неотвеченный вопрос. Читает его только {@code POST /runs}: в очередь идущего прогона
 *     повторять нечего
 */
public record StartRunRequest(
        @Nullable String text,
        @Nullable List<ContextItemRequest> contextItems,
        @Nullable String model,
        @Nullable String mode,
        @Nullable String project,
        @Nullable String clientMsgId,
        boolean retry) {}
