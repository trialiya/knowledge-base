package io.github.trialiya.kb.service.chat.memory;

import static io.github.trialiya.kb.model.chat.dto.ChatEventType.COMPACT_APPLIED;

import io.github.trialiya.kb.config.model.SummarizeProperties;
import io.github.trialiya.kb.model.chat.dto.CompactPayload;
import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import io.github.trialiya.kb.model.chat.entity.ChatMessageMeta;
import io.github.trialiya.kb.model.chat.entity.ChatPendingSummaryEntity;
import io.github.trialiya.kb.model.chat.entity.CompactMeta;
import io.github.trialiya.kb.model.chat.entity.RunTokenUsage;
import io.github.trialiya.kb.repository.ChatMessageRepository;
import io.github.trialiya.kb.repository.ChatPendingSummaryRepository;
import io.github.trialiya.kb.service.chat.event.ChatEventService;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Когда написанная сводка попадает в историю — единственный писатель и читатель {@code
 * chat_pending_summary}.
 *
 * <p>Сжатие меняет начало истории, а провайдер кэширует промпт с первого байта: пока кэш чата
 * горячий, подмена куска стоит полной оплаты следующего запроса. Поэтому раунд не применяет свой
 * результат сам, а {@link #park паркует} его, и применение ждёт повода:
 *
 * <ul>
 *   <li>{@link #applyIfPaused} — в разговоре была пауза (меряется от последнего ряда чата, см.
 *       {@code ChatMessageRepository#lastCreatedAt}): кэш всё равно протух, и подмена бесплатна.
 *       Это обычный путь, и зовут его перед новым вопросом — то есть ровно тогда, когда сжатая
 *       история впервые понадобится;
 *   <li>{@link #applyIfOversized} — ждать больше нельзя: контекст дорос до предела, с которого
 *       начинает быть дорогим сам разговор. Предел зависит от окна модели и потому приходит
 *       параметром (см. {@code ChatModelProperties.ModelOption#contextTokens}).
 * </ul>
 *
 * <p>Пока сводка припаркована, начало истории всё ещё живое: модель видит её несжатой, а следующий
 * раунд по тому же куску не начинается ({@link #isParked}). Плата за отложенность — эти несколько
 * запросов на несжатой истории; выигрыш — целый кэш чата, который стоит дороже.
 */
@Slf4j
@Service
public class PendingSummaryService {

    private final ChatPendingSummaryRepository repository;
    private final ChatMessageRepository chatMessages;
    private final SummaryWriter summaryWriter;
    private final ChatEventService events;
    private final SummarizeProperties properties;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    public PendingSummaryService(
            ChatPendingSummaryRepository repository,
            ChatMessageRepository chatMessages,
            SummaryWriter summaryWriter,
            ChatEventService events,
            SummarizeProperties properties,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.chatMessages = chatMessages;
        this.summaryWriter = summaryWriter;
        this.events = events;
        this.properties = properties;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /** Ждёт ли этот чат применения сводки. */
    public boolean isParked(String conversationId) {
        return repository.findByConversationId(conversationId).isPresent();
    }

    /**
     * Откладывает написанную сводку до подходящего момента. Звать под замком чата ({@code
     * SummaryWriter#inConversation}): парковка закрывает окно для следующего раунда, и без замка
     * два раунда сжали бы один и тот же кусок.
     *
     * @param stats числа для будущей плашки; {@code kind} у припаркованной сводки всегда {@link
     *     CompactMeta.Kind#SUMMARIZE} — команду пользователя ждать незачем, её применяют сразу
     */
    public void park(
            String conversationId, SummaryWriter.SummaryRow row, SummaryWriter.CompactStats stats) {
        repository.deleteByConversationId(conversationId);
        repository.save(
                new ChatPendingSummaryEntity(
                        0L,
                        conversationId,
                        row.startPosition(),
                        row.endPosition(),
                        row.position(),
                        row.createdAt(),
                        row.text(),
                        stats.messages(),
                        stats.summaryChars(),
                        parkedMeta(row.trace(), stats.usage()),
                        LocalDateTime.now(clock)));
        log.info(
                "[{}] Summary parked: positions {}-{}, {} messages — waiting for a pause or the"
                        + " context limit",
                conversationId,
                row.startPosition(),
                row.endPosition(),
                stats.messages());
    }

    /**
     * Применяет сводку, если разговор достаточно долго молчал. Зовётся перед новым вопросом, а
     * значит пауза здесь — это пауза ДО него: следующий ряд ещё не записан, и {@code lastCreatedAt}
     * отвечает про последний обмен репликами. Не бросает — см. {@link #apply}.
     */
    public void applyIfPaused(String conversationId) {
        apply(
                conversationId,
                parked -> {
                    final Optional<LocalDateTime> last = chatMessages.lastCreatedAt(conversationId);
                    if (last.isEmpty()) {
                        return null;
                    }
                    final Duration idle = Duration.between(last.get(), LocalDateTime.now(clock));
                    return idle.compareTo(properties.applyAfter()) >= 0
                            ? "the chat has been idle for " + idle.toSeconds() + "s"
                            : null;
                });
    }

    /**
     * Применяет сводку, если контекст дорос до предела, с которого ждать паузы уже дороже, чем
     * потерять кэш. Не бросает — см. {@link #apply}.
     *
     * @param contextTokens замер последнего прогона; {@code 0} — прогон не измерен, и судить не по
     *     чему: такой чат дождётся паузы
     * @param modelContextTokens окно модели, на которой шёл прогон; {@code null} — окно этой модели
     *     не названо в конфигурации, и порога у чата нет вовсе
     */
    public void applyIfOversized(
            String conversationId, long contextTokens, @Nullable Integer modelContextTokens) {
        if (contextTokens <= 0 || modelContextTokens == null) {
            return;
        }
        final long limit = Math.round(modelContextTokens * properties.applyAtRatio());
        apply(
                conversationId,
                parked ->
                        contextTokens >= limit
                                ? "the context reached "
                                        + contextTokens
                                        + " of "
                                        + limit
                                        + " tokens"
                                : null);
    }

    /**
     * Забывает припаркованную сводку. Зовёт {@code /compact}: он заменяет сводкой весь контекст, и
     * отложенная сводка после него описывает историю, которой в промпте больше нет.
     */
    public void discard(String conversationId) {
        if (repository.deleteByConversationId(conversationId) > 0) {
            log.info("[{}] Parked summary discarded — the context was compacted", conversationId);
        }
    }

    /**
     * Общий ход применения: заявка на строку, затем запись. Замок берём без ожидания — зовут отсюда
     * с пути живого запроса, а раунд сжатия под тем же замком держит обращение к модели; повод
     * применить придёт снова, и терять на нём секунды ответа не за что.
     *
     * @param due почему применяем — {@code null} значит «ещё рано»; строка едет в лог, потому что
     *     иначе по логам не отличить бесплатное применение от вынужденного
     */
    private void apply(String conversationId, DueCheck due) {
        // Не бросаем ничего: применение — это оптимизация цены, и зовут её с чужих путей, где
        // прогон уже начат или ещё только начинается. Упавшая оптимизация не повод отказать
        // пользователю в вопросе (start) и тем более не повод не доставить очередь и не снять
        // заявку на чат (onTerminal) — сводка просто останется припаркованной до следующего повода.
        try {
            final Optional<ChatPendingSummaryEntity> parked =
                    repository.findByConversationId(conversationId);
            if (parked.isEmpty()) {
                return;
            }
            final @Nullable String reason = due.reason(parked.get());
            if (reason == null) {
                return;
            }
            final boolean ran =
                    summaryWriter.tryInConversation(
                            conversationId, () -> write(conversationId, parked.get(), reason));
            if (!ran) {
                log.debug(
                        "[{}] Skipping summary apply — a compaction round holds the chat",
                        conversationId);
            }
        } catch (Exception e) {
            log.error(
                    "[{}] Applying the parked summary failed: {}",
                    conversationId,
                    e.getMessage(),
                    e);
        }
    }

    private void write(String conversationId, ChatPendingSummaryEntity parked, String reason) {
        final @Nullable ChatMessageEntity notice =
                transactionTemplate.execute(s -> claimAndWrite(conversationId, parked));
        if (notice == null) {
            return;
        }
        log.info(
                "[{}] Summary applied: positions {}-{} are no longer live — {}",
                conversationId,
                parked.getStartPosition(),
                parked.getEndPosition(),
                reason);
        // Плашка встаёт в СЕРЕДИНУ ленты — там, где кончается свёрнутое, — поэтому вкладке мало
        // «допиши в конец»: место она ищет сама, по времени плашки (см. chatEventReducer).
        events.publish(conversationId, COMPACT_APPLIED, null, null, CompactPayload.of(notice));
    }

    /**
     * Заявка на строку и запись по ней — одной транзакцией. Заявка через удаление: точек применения
     * несколько (пауза перед вопросом, предел контекста в конце прогона), и без неё две из них,
     * сойдясь на одном чате, записали бы одну сводку двумя рядами.
     *
     * <p>Транзакция здесь ровно затем, чтобы удаление не пережило неудавшуюся запись: сводку писала
     * модель, и второй раз её никто не напишет — {@link SummarizeService} следующий раунд по этому
     * куску не начнёт, кусок-то уже не живой. Откат возвращает строку на место, и повод применить
     * придёт снова. Запись при этом идёт своей транзакцией ({@code SummaryWriter#writeCompacted}) —
     * она присоединяется к этой, поэтому разметка, сводка и плашка по-прежнему неделимы.
     *
     * @return {@code null} — строку забрал кто-то другой, и применять нечего
     */
    private @Nullable ChatMessageEntity claimAndWrite(
            String conversationId, ChatPendingSummaryEntity parked) {
        if (repository.claim(parked.getId()) == 0) {
            return null;
        }
        final @Nullable ChatMessageMeta meta = parked.getMeta();
        return summaryWriter.writeCompacted(
                new SummaryWriter.SummaryRow(
                        conversationId,
                        parked.getStartPosition(),
                        parked.getEndPosition(),
                        parked.getSummaryPosition(),
                        parked.getSummaryCreatedAt(),
                        parked.getText(),
                        new ProjectTrace(
                                meta == null ? List.of() : meta.visitedProjects(),
                                meta == null ? null : meta.project())),
                new SummaryWriter.CompactStats(
                        CompactMeta.Kind.SUMMARIZE,
                        parked.getMessages(),
                        parked.getSummaryChars(),
                        meta == null ? null : meta.usage()));
    }

    /**
     * Мета парковки: спаны проектов уедут на строку-сводку, замер раунда — на плашку. Считаются оба
     * в момент парковки — позже не из чего: сжатый кусок к тому времени всё ещё живой, но раунд,
     * который его читал и оплатил, давно кончился.
     */
    private static @Nullable ChatMessageMeta parkedMeta(
            ProjectTrace trace, @Nullable RunTokenUsage usage) {
        final boolean noTrace = trace.lastProject() == null && trace.spans().isEmpty();
        if (noTrace) {
            return usage == null ? null : ChatMessageMeta.ofUsage(usage);
        }
        final ChatMessageMeta meta = ChatMessageMeta.ofProject(trace.lastProject(), trace.spans());
        return usage == null ? meta : meta.withUsage(usage);
    }

    /** Повод применить сводку или {@code null}, если его ещё нет. */
    @FunctionalInterface
    private interface DueCheck {
        @Nullable String reason(ChatPendingSummaryEntity parked);
    }
}
