package io.github.trialiya.kb.model.chat.dto;

import org.jspecify.annotations.Nullable;

/**
 * Тело {@code POST /compact}: команда {@code /compact <текст>} целиком.
 *
 * @param text сообщение, как оно набрано (с самим {@code /compact}) — сохраняется обычным
 *     USER-сообщением, чтобы команда осталась видна в истории, как и любая другая реплика
 * @param instructions хвост команды — на чём сосредоточиться при сжатии; {@code null} или пустая
 *     строка — обычное сжатие без фокуса. В окно, которое уходит модели на сжатие, не входит: это
 *     инструкция сжатию, а не материал для него (см. {@code CompactService})
 * @param clientMsgId идентификатор клиента-отправителя — тот же смысл, что у {@link
 *     StartRunRequest#clientMsgId}
 */
public record CompactRequest(
        String text, @Nullable String instructions, @Nullable String clientMsgId) {}
