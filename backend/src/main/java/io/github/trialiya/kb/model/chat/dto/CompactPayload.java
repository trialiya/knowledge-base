package io.github.trialiya.kb.model.chat.dto;

import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import io.github.trialiya.kb.model.chat.entity.CompactMeta;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Нагрузка события {@link ChatEventType#COMPACT_DONE}: чем закончилось сжатие контекста.
 *
 * <p>Собирается из строки-плашки, которую сжатие оставило в истории (см. {@code
 * SummaryWriter.writeCompacted}), а не считается заново: живая вкладка и вкладка, открытая после
 * перезагрузки, обязаны показать одну и ту же плашку с одним и тем же временем и одними и теми же
 * числами — а перезагруженная берёт их только из истории.
 *
 * @param messageId id строки-плашки — адрес деталей сжатия ({@code GET /compact?messageId=…})
 * @param messages сколько сообщений перестало ехать модели — живое окно (включая протокольные
 *     TOOL-строки и уже существовавшие сводки) плюс сама команда {@code /compact}: её текст в
 *     сжатие не попал, но помечается сжатой вместе с окном, и дальше модель её тоже не увидит
 * @param summaryChars длина написанного моделью документа в символах — того самого, который
 *     показывает модалка деталей. С {@code messages} это ответ на «во сколько раз сжали», который
 *     иначе виден только в логах
 * @param createdAt время завершения раунда — оно же время плашки в ленте
 */
public record CompactPayload(
        long messageId, int messages, int summaryChars, LocalDateTime createdAt) {

    public static CompactPayload of(ChatMessageEntity notice) {
        final CompactMeta compact =
                Objects.requireNonNull(
                        notice.getMeta() == null ? null : notice.getMeta().compact(),
                        "not a compaction notice row");
        return new CompactPayload(
                notice.getId(), compact.messages(), compact.summaryChars(), notice.getCreatedAt());
    }
}
