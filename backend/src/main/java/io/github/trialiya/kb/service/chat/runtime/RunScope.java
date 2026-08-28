package io.github.trialiya.kb.service.chat.runtime;

import io.github.trialiya.kb.model.chat.entity.RunTokenUsage;
import io.github.trialiya.kb.model.chat.entity.TokenUsage;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import reactor.core.Disposable;

/**
 * Всё, что живёт ровно столько же, сколько один прогон, — в одном объекте с одним временем жизни.
 * Заводит его {@code ChatRunService.start}, снимает с учёта терминальная обработка; кто нашёл
 * область по {@code runId}, тот и работает с живым прогоном, а не нашёл — прогона больше нет.
 *
 * <p>Снятая с учёта область продолжает жить у того, кто её держит: терминальная обработка ещё
 * дописывает по ней ответ и токены в БД. «Нет в реестре» значит «новых дел прогону не давать», а не
 * «объект мёртв».
 *
 * <p>Одним объектом, а не тремя реестрами (дескриптор, счёт токенов, нумерация вызовов): у всех
 * трёх одна и та же граница жизни, и каждый отдельный реестр требовал своей дисциплины очистки,
 * своего счётчика утечек в {@code ChatRuntimeMonitor} и — у нумерации вызовов — блокировки от
 * воскрешения состояния уже мёртвого прогона. Здесь воскрешать нечего: запись заводит только
 * владелец.
 *
 * <p>Собственных потоков у прогона нет, но пишут в него с разных: подписка на стрим ставит {@link
 * #attach}, остановка приходит из HTTP-запроса или из shutdown, токены считает advisor изнутри
 * tool-цикла, вызовы нумерует запись истории. Поэтому каждое поле здесь либо атомарно, либо под
 * {@link #numbering}.
 */
public final class RunScope {

    private final String runId;
    private final String conversationId;
    private final String user;
    private final String model;
    private final long startedAtNanos;

    private final AtomicReference<Disposable> disposable = new AtomicReference<>();
    private final AtomicBoolean stopRequested = new AtomicBoolean();
    private final AtomicBoolean persisted = new AtomicBoolean();
    private final AtomicReference<RunTokenUsage.Tally> tally =
            new AtomicReference<>(RunTokenUsage.Tally.EMPTY);

    /** Нумерация вызовов инструментов прогона — состояние под собственным замком. */
    private final Numbering numbering = new Numbering();

    RunScope(String runId, String conversationId, String user, String model) {
        this.runId = runId;
        this.conversationId = conversationId;
        this.user = user;
        this.model = model;
        // Монотонные часы: системное время могут перевести посреди прогона, и таймер над полем
        // ввода прыгнул бы вместе с ним.
        this.startedAtNanos = System.nanoTime();
    }

    public String runId() {
        return runId;
    }

    public String conversationId() {
        return conversationId;
    }

    public String user() {
        return user;
    }

    /**
     * id модели прогона, уже разрешённый (см. {@code ChatClientRegistry#resolveModelId}): им
     * помечаются написанные прогоном ответы, а «модель по умолчанию» пометкой быть не может —
     * дефолт в конфигурации меняют.
     */
    public String model() {
        return model;
    }

    /** Сколько миллисекунд прогон уже идёт — длительность для таймера над полем ввода. */
    public long elapsedMs() {
        return (System.nanoTime() - startedAtNanos) / 1_000_000;
    }

    // ── Остановка ────────────────────────────────────────────────────────────

    /**
     * Сигнал остановки. Флаг ставим ДО чтения disposable, а {@link #attach} ставит disposable ДО
     * чтения флага — так остановка не проваливается в окно между постановкой задачи в пул и
     * подпиской на стрим (иначе прогон остался бы неостанавливаемым до конца генерации).
     */
    public void cancel() {
        stopRequested.set(true);
        final Disposable subscription = disposable.get();
        if (subscription != null && !subscription.isDisposed()) {
            subscription.dispose();
        }
    }

    /**
     * Запоминает подписку на стрим и сразу гасит её, если остановку успели запросить раньше, —
     * вторая половина окна из {@link #cancel}.
     */
    public void attach(Disposable subscription) {
        disposable.set(subscription);
        if (stopRequested.get()) {
            subscription.dispose();
        }
    }

    /** Флаг остановки — его же читают инструменты прогона через {@code RunCancellation}. */
    public AtomicBoolean stopRequested() {
        return stopRequested;
    }

    /**
     * Занимает право сохранить ответ прогона: {@code true} достаётся ровно одному вызову.
     * Терминальных сигналов у оборванного стрима бывает два ({@code onError} и {@code doFinally}),
     * и без этого частичный ответ сохранился бы дважды.
     */
    public boolean claimPersist() {
        return persisted.compareAndSet(false, true);
    }

    // ── Токены ───────────────────────────────────────────────────────────────

    /**
     * Добавляет к итогу прогона замер одного законченного обращения к модели.
     *
     * @return новый итог прогона
     */
    public RunTokenUsage addCall(TokenUsage call) {
        return tally.updateAndGet(current -> current.with(call)).view();
    }

    /**
     * Итог прогона с учётом ещё не закрытого обращения к модели — то, что показывают по ходу
     * генерации. Ничего не накапливает: {@code pending} доедет до итога отдельно, через {@link
     * #addCall}, когда обращение закончится.
     */
    public RunTokenUsage usageWith(TokenUsage pending) {
        return tally.get().with(pending).view();
    }

    /** Накопленное прогоном на эту секунду. */
    public RunTokenUsage usage() {
        return tally.get().view();
    }

    // ── Нумерация вызовов инструментов ───────────────────────────────────────

    /**
     * Сквозной номер вызова внутри прогона — тот же, что считает {@code ToolInvocationCollector},
     * поэтому по нему фронт склеивает живую плашку с итоговой метой. Скрытые инструменты номер
     * занимают, но событий не порождают, поэтому нумерует ЛЮБОЙ вызов, а решает о показе
     * вызывающий.
     */
    public int nextCallIndex() {
        return numbering.next();
    }

    /** Запоминает разобранные аргументы вызова — ими дополняется событие его ответа. */
    public void rememberCall(String callId, int callIndex, Map<Object, Object> arguments) {
        numbering.remember(callId, callIndex, arguments);
    }

    /**
     * Что было известно о вызове, когда он начинался; {@code null} — вызова прогон не нумеровал
     * (событие его начала ушло до того, как этот прогон завёл область). Событие ответа без номера и
     * аргументов фронт склеить не сможет, и пустое хуже отсутствующего.
     */
    public @Nullable StartedCall startedCall(String callId) {
        return numbering.started(callId);
    }

    /** Номер вызова и его аргументы — то, чем событие ответа дополняется до полной плашки. */
    public record StartedCall(int callIndex, Map<Object, Object> arguments) {}

    private static final class Numbering {

        private final Map<String, StartedCall> byCallId = new HashMap<>();
        private int next;

        synchronized int next() {
            return next++;
        }

        synchronized void remember(String callId, int callIndex, Map<Object, Object> arguments) {
            byCallId.put(callId, new StartedCall(callIndex, arguments));
        }

        synchronized @Nullable StartedCall started(String callId) {
            return byCallId.get(callId);
        }
    }
}
