package io.github.trialiya.kb.model.chat.dto;

/**
 * Конверт одного события в канале чата.
 *
 * @param seq монотонный (в рамках чата) номер — даёт порядок и точку дозагрузки при переподключении
 * @param type тип события
 * @param runId к какому прогону генерации относится (null для чисто служебных событий)
 * @param clientMsgId идентификатор клиента-инициатора — чтобы вкладка-отправитель не задвоила свой
 *     оптимистично показанный пузырь
 * @param payload полезная нагрузка, зависит от {@link #type}
 */
public record ChatEvent(
        long seq, ChatEventType type, String runId, String clientMsgId, Object payload) {}
