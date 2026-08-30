package io.github.trialiya.kb.model.chat.dto;

import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import io.github.trialiya.kb.model.chat.entity.ChatMessageMeta;
import io.github.trialiya.kb.model.chat.entity.CompactMeta;
import io.github.trialiya.kb.model.chat.entity.RunTokenUsage;
import java.time.LocalDateTime;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Нагрузка событий {@link ChatEventType#COMPACT_DONE} и {@link ChatEventType#COMPACT_APPLIED}: чем
 * закончилось сжатие контекста.
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
 * @param kind чем сжатие вызвано; вкладке этого мало знать для текста плашки — от вида зависит и
 *     то, считать ли по этому замеру экономию (см. {@code tokenUsage.js})
 * @param createdAt время плашки в ленте: у полного сжатия — время завершения раунда, у фоновой
 *     сводки — время последнего свёрнутого сообщения, потому что встаёт она туда же, где кончается
 *     свёрнутое
 * @param usage токены раунда сжатия ({@code null} — эндпоинт замера не отдал). Едут в событии по
 *     той же причине, что и всё остальное здесь: вкладка, которая сжатие дождалась, обязана
 *     показать плашку такой же, какой её увидит перезагруженная, — а та берёт эти числа из меты
 *     ряда
 */
public record CompactPayload(
        long messageId,
        int messages,
        int summaryChars,
        CompactMeta.Kind kind,
        LocalDateTime createdAt,
        @Nullable RunTokenUsage usage) {

    public static CompactPayload of(ChatMessageEntity notice) {
        final ChatMessageMeta meta =
                Objects.requireNonNull(notice.getMeta(), "not a compaction notice row");
        final CompactMeta compact =
                Objects.requireNonNull(meta.compact(), "not a compaction notice row");
        return new CompactPayload(
                notice.getId(),
                compact.messages(),
                compact.summaryChars(),
                compact.kind(),
                notice.getCreatedAt(),
                meta.usage());
    }
}
