package io.github.trialiya.kb.service.chat.memory;

import com.google.common.util.concurrent.Striped;
import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import io.github.trialiya.kb.model.chat.entity.ChatMessageMeta;
import io.github.trialiya.kb.model.chat.entity.CompactMeta;
import io.github.trialiya.kb.model.chat.entity.RunTokenUsage;
import io.github.trialiya.kb.repository.ChatMessageRepository;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.concurrent.locks.Lock;
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

    /**
     * Замок на чат, под которым идёт весь раунд сжатия — от чтения окна до записи сводки, а не одна
     * только запись. Обе операции сначала читают живое окно, а потом объявляют его сжатым; два
     * раунда, прочитавшие одно и то же окно, запишут поверх него две сводки, и вторая накроет
     * материал, который первая уже заменила. Занятость чата ({@code ChatRunService}) от этого не
     * спасает: фоновая суммаризация стартует по RUN_DONE и живёт уже вне занятого слота.
     *
     * <p>{@link Striped} — потому что чатов много, а замок нужен на один: полосы дают постоянную
     * память вместо карты, которую пришлось бы чистить.
     */
    private final Striped<Lock> locks = Striped.lock(1024);

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
     *     идёт по нему, а уже потом по позиции). У {@code /compact} это время завершения раунда:
     *     живого хвоста после него не остаётся, поэтому сводке нечего обгонять, зато её время видит
     *     пользователь — на строке-плашке (см. {@link #writeCompacted})
     * @param trace след проектов сжатой части — то, чем сводка отвечает на «в каком репозитории
     *     читан файл из сообщения 40» после того, как само сообщение уехало из окна
     */
    public record SummaryRow(
            String conversationId,
            long startPosition,
            long endPosition,
            long position,
            LocalDateTime createdAt,
            String text,
            ProjectTrace trace) {}

    /**
     * Выполняет раунд сжатия чата под замком этого чата (см. {@link #locks}). Обёртывать нужно всё
     * целиком — и чтение окна, и вызов модели, и {@link #writeCompacted}: замок вокруг одной записи
     * развёл бы транзакции во времени, но не помешал бы обоим раундам прочитать одно окно.
     */
    public void inConversation(String conversationId, Runnable round) {
        final Lock lock = locks.get(conversationId);
        lock.lock();
        try {
            round.run();
        } finally {
            lock.unlock();
        }
    }

    /**
     * То же, но не дожидаясь освобождения чата: {@code false} — раунд сжатия в этом чате уже идёт,
     * и работа пропущена целиком. Для применения уже написанной сводки ({@code
     * PendingSummaryService}): его зовут с пути живого запроса, где ждать чужого обращения к модели
     * нельзя, а откладывать применение — можно, следующий повод придёт скоро.
     */
    public boolean tryInConversation(String conversationId, Runnable round) {
        final Lock lock = locks.get(conversationId);
        if (!lock.tryLock()) {
            return false;
        }
        try {
            round.run();
        } finally {
            lock.unlock();
        }
        return true;
    }

    /**
     * Помечает старые сообщения сжатыми, вставляет строку-сводку и видимую строку-плашку «контекст
     * сжат» — одним действием, потому что смысла порознь у них нет: сводка без плашки — молча
     * исчезнувшая на перезагрузке история сжатия, плашка без сводки — ссылка в никуда.
     *
     * <p>Плашка — отдельный ряд, а не сама сводка, потому что у них противоположные роли: сводка
     * уезжает модели и не показывается ({@code summary = true}), плашка показывается и модели не
     * уезжает ({@code summarized = true}). Ни один флаг по отдельности такого ряда не описывает,
     * поэтому их два, и цена — один лишний ряд на сжатие.
     *
     * <p>Позиция плашки — сразу за сводкой, и занятой она быть вправе: фоновая суммаризация
     * оставляет за собой живой хвост, и тот же номер уже носит его первый ряд. Порядок от этого не
     * страдает — история и в ленте, и в промпте читается по времени, а номер плашке нужен только
     * затем, чтобы следующий раунд накрыл её своей разметкой (модели она не едет и так).
     *
     * @param stats числа для самой плашки; записью, а не парой {@code int}, — в позиционном вызове
     *     они меняются местами без единой ошибки компиляции
     * @return строка-плашка; её id уезжает в {@code COMPACT_DONE} и служит адресом деталей сжатия
     */
    public ChatMessageEntity writeCompacted(SummaryRow row, CompactStats stats) {
        return Objects.requireNonNull(
                transactionTemplate.execute(
                        s -> {
                            final ChatMessageEntity summary = saveSummary(row);
                            final ChatMessageMeta meta =
                                    ChatMessageMeta.ofCompact(
                                            new CompactMeta(
                                                    stats.messages(),
                                                    stats.summaryChars(),
                                                    summary.getId(),
                                                    stats.kind()));
                            return chatMessageRepository.save(
                                    new ChatMessageEntity(
                                            0L,
                                            row.conversationId(),
                                            "",
                                            MessageType.ASSISTANT,
                                            row.position() + 1,
                                            true,
                                            false,
                                            row.createdAt(),
                                            stats.usage() == null
                                                    ? meta
                                                    : meta.withUsage(stats.usage())));
                        }));
    }

    /**
     * Числа для плашки сжатия.
     *
     * @param kind чем сжатие вызвано — команда пользователя, предел контекста или фоновая
     *     суммаризация (см. {@link CompactMeta.Kind}); плашка у них одна, а читаются они по-разному
     * @param messages сколько сообщений перестало ехать модели
     * @param summaryChars длина документа, который написала модель, — без обёртки, в которую сводка
     *     попадает в {@code row.text()}: плашка и модалка показывают это число рядом с самим
     *     документом, и считать его надо по нему же
     * @param usage токены раунда — тем же полем меты, что и у обычного ответа ({@code
     *     ChatMessageMeta#usage}), чтобы итог по чату считался по всем рядам одним правилом. {@code
     *     null} — эндпоинт замера не отдал. Заголовочный {@code contextTokens} здесь описывает
     *     контекст самого раунда (сжатое окно плюс сводка), а не то, что осталось в чате после
     *     него: раунд читал историю целиком, и это последнее место, где она была измерена
     */
    public record CompactStats(
            CompactMeta.Kind kind, int messages, int summaryChars, @Nullable RunTokenUsage usage) {}

    /** Разметка сжатого куска и сама строка-сводка; вызывать только внутри транзакции. */
    private ChatMessageEntity saveSummary(SummaryRow row) {
        chatMessageRepository.updateSummarized(
                row.conversationId(), row.startPosition(), row.endPosition());
        return chatMessageRepository.save(
                new ChatMessageEntity(
                        0L,
                        row.conversationId(),
                        row.text(),
                        MessageType.ASSISTANT,
                        row.position(),
                        false,
                        true,
                        row.createdAt(),
                        metaOf(row.trace())));
    }

    /**
     * Мета строки-сводки: спаны — то, что читает промпт; одинокий {@code project} пишется рядом
     * ради отката на версию, которая спанов ещё не знает (см. {@link ChatMessageMeta}).
     */
    private static @Nullable ChatMessageMeta metaOf(ProjectTrace trace) {
        if (trace.lastProject() == null && trace.spans().isEmpty()) {
            return null;
        }
        return ChatMessageMeta.ofProject(trace.lastProject(), trace.spans());
    }
}
