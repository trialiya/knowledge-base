package io.github.trialiya.kb.service.chat.memory;

import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import io.github.trialiya.kb.model.chat.entity.ChatMessageMeta;
import io.github.trialiya.kb.repository.ChatMessageRepository;
import java.time.LocalDateTime;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Подмена куска истории одной строкой-сводкой — общая для фоновой суммаризации ({@link
 * SummarizeService}) и для команды {@code /compact} ({@link CompactService}). Обе операции решают
 * по-разному, что именно сжать, но записывают результат одинаково, и записывать его обязательно
 * одной транзакцией: разметка без сводки — это молча потерянная история.
 */
@Service
public class SummaryWriter {

    private final ChatMessageRepository chatMessageRepository;
    private final TransactionTemplate transactionTemplate;

    public SummaryWriter(
            ChatMessageRepository chatMessageRepository,
            PlatformTransactionManager transactionManager) {
        this.chatMessageRepository = chatMessageRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * Что записать: диапазон, который перестаёт быть живым, и строка, которая встаёт вместо него.
     *
     * <p>Записью, а не списком аргументов: пять из семи полей — {@code long} и строки подряд, в
     * позиционном вызове они меняются местами без единой ошибки компиляции, а ценой такой
     * перестановки будет размеченный не тот кусок истории.
     *
     * @param startPosition первая позиция, которую помечаем сжатой
     * @param endPosition последняя помечаемая позиция — не обязательно та, до которой читала
     *     модель: за последним сжатым ходом тянутся пустые протокольные TOOL-строки, и они обязаны
     *     попасть под ту же разметку, иначе останутся живыми и осиротевшими
     * @param position позиция самой сводки — позиция последнего сжатого сообщения, чтобы при
     *     следующем чтении сводка встала перед живым хвостом
     * @param createdAt время сводки — время последнего сжатого сообщения (порядок чтения истории
     *     идёт по нему, а уже потом по позиции)
     * @param project проект, на котором закончилась сжатая часть; {@code null} — чат никуда не
     *     переезжал, и проект самого чата всё покрывает
     */
    public record SummaryRow(
            String conversationId,
            long startPosition,
            long endPosition,
            long position,
            LocalDateTime createdAt,
            String text,
            @Nullable String project) {}

    /** Помечает старые сообщения сжатыми и вставляет строку-сводку — атомарно. */
    public void write(SummaryRow row) {
        transactionTemplate.executeWithoutResult(
                s -> {
                    chatMessageRepository.updateSummarized(
                            row.conversationId(), row.startPosition(), row.endPosition());
                    chatMessageRepository.save(
                            new ChatMessageEntity(
                                    0L,
                                    row.conversationId(),
                                    row.text(),
                                    MessageType.ASSISTANT,
                                    row.position(),
                                    false,
                                    true,
                                    row.createdAt(),
                                    row.project() == null
                                            ? null
                                            : ChatMessageMeta.ofProject(row.project())));
                });
    }

    /**
     * На каком проекте закончился сжимаемый кусок — след смены проекта, который иначе исчез бы
     * вместе со своим маркером. {@code null} — смены внутри куска не было.
     */
    public static @Nullable String lastProject(Stream<ChatMessageEntity> rows) {
        return rows.map(ChatMessageEntity::getMeta)
                .filter(meta -> meta != null && meta.project() != null)
                .reduce((first, second) -> second)
                .map(ChatMessageMeta::project)
                .orElse(null);
    }
}
