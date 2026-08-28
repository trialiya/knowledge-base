package io.github.trialiya.kb.service.chat.event;

import io.github.trialiya.kb.model.chat.dto.ChatEvent;
import io.github.trialiya.kb.model.chat.dto.ChatEventType;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Канал одного чата: подписчики ({@link SseEmitter} всех открытых вкладок) + упорядоченный лог
 * событий текущего прогона для дозагрузки (replay).
 *
 * <p>Лог хранит события только активного прогона: он очищается в начале нового ({@link #startRun})
 * и по завершении ({@link #endRun}). Так поздно подключившаяся / перезагруженная вкладка догоняет
 * ответ «на лету», а уже завершённый ответ не реплеится повторно — он лежит в БД и грузится обычным
 * запросом истории.
 *
 * <p>Длина лога ограничена {@link #MAX_LOG_EVENTS}: событий у прогона столько же, сколько токенов в
 * ответе, плюс вызовы инструментов, а конца у прогона может и не наступить. Из переполненного лога
 * уходит самое старое, и вкладке, которой выброшенное предназначалось, об этом говорят — событием
 * {@link ChatEventType#REPLAY_GAP} перед реплеем. Молчать здесь нельзя: вкладка собирает ответ из
 * чанков подряд и склеила бы куски с дырой посередине, ничего не заметив.
 *
 * <p>Состояние защищено {@link ReentrantLock} (а не {@code synchronized}): отправка событий идёт
 * под локом и делает блокирующий I/O ({@link SseEmitter#send}), а вызывается в т.ч. с виртуальных
 * потоков пула генерации — на {@code synchronized} это привязывало бы carrier-поток (pinning) до
 * JDK 24. Лок на хаб, а не общий (например, Striped по chatId): иначе медленный клиент одного чата
 * блокировал бы публикацию в другие чаты, попавшие на тот же stripe.
 *
 * <p>{@link #closed} закрывает гонку «выгрузка простаивающего хаба ↔ новая подписка»: закрытый хаб
 * больше не принимает подписчиков, а {@link ChatEventService} в этом случае выбрасывает его из
 * реестра и повторяет на свежем.
 */
@Slf4j
public class ConversationHub {

    /**
     * Потолок лога реплея. Порядок величины — длинный ответ целиком: сотни чанков и десятки вызовов
     * инструментов помещаются, а прогон, который льёт часами, не растёт в памяти без предела.
     */
    private static final int MAX_LOG_EVENTS = 2000;

    private final String conversationId;
    private final ReentrantLock lock = new ReentrantLock();
    private final List<ChatEvent> eventLog = new ArrayList<>();
    private final List<SseEmitter> subscribers = new ArrayList<>();

    /** Колбэк «хаб простаивает» — реестр пытается выгрузить его (см. {@link ChatEventService}). */
    @Nullable private final Consumer<ConversationHub> onIdle;

    private long seq;

    /** Наибольший seq, выброшенный из переполненного лога; 0 — не выброшено ничего. */
    private long droppedThroughSeq;

    @Nullable private String activeRunId;
    private boolean closed;

    public ConversationHub(String conversationId, @Nullable Consumer<ConversationHub> onIdle) {
        this.conversationId = conversationId;
        this.onIdle = onIdle;
        log.debug("[{}] hub created", conversationId);
    }

    public String conversationId() {
        return conversationId;
    }

    /**
     * Подписывает вкладку, сразу реплея пропущенные ею события (seq &gt; {@code fromSeq}).
     * Возвращает {@code null}, если хаб уже закрыт (выгружается из реестра) — вызывающий должен
     * повторить на свежем.
     */
    @Nullable
    public SseEmitter subscribe(long fromSeq, long timeoutMillis) {
        final SseEmitter emitter = new SseEmitter(timeoutMillis);
        lock.lock();
        try {
            if (closed) {
                return null;
            }
            // Честная дыра: часть того, что вкладка ждала, из лога уже выброшена. seq у этого
            // события — seq последнего выброшенного: курсор вкладки встаёт ровно туда, откуда
            // реплей и продолжится, и второй раз на ту же дыру она не наткнётся.
            if (droppedThroughSeq > fromSeq) {
                send(
                        emitter,
                        new ChatEvent(
                                droppedThroughSeq,
                                ChatEventType.REPLAY_GAP,
                                activeRunId,
                                null,
                                null));
            }
            for (final ChatEvent event : eventLog) {
                if (event.seq() > fromSeq) {
                    send(emitter, event);
                }
            }
            subscribers.add(emitter);
            log.debug("[{}] subscriber added, total={}", conversationId, subscribers.size());
        } finally {
            lock.unlock();
        }
        emitter.onCompletion(
                () -> {
                    log.debug("[{}] emitter completed (client closed)", conversationId);
                    remove(emitter);
                });
        emitter.onTimeout(
                () -> {
                    log.debug("[{}] emitter timed out", conversationId);
                    emitter.complete();
                    remove(emitter);
                });
        emitter.onError(
                e -> {
                    log.debug("[{}] emitter error: {}", conversationId, e.getMessage());
                    remove(emitter);
                });
        return emitter;
    }

    public ChatEvent publish(
            ChatEventType type,
            @Nullable String runId,
            @Nullable String clientMsgId,
            @Nullable Object payload) {
        lock.lock();
        try {
            final ChatEvent event = new ChatEvent(++seq, type, runId, clientMsgId, payload);
            eventLog.add(event);
            while (eventLog.size() > MAX_LOG_EVENTS) {
                droppedThroughSeq = eventLog.removeFirst().seq();
            }
            // Обходим подписчиков без копирования (это горячий путь — на каждый токен). Безопасно:
            // send() сам глотает ошибку отправки, а контейнерные колбэки onError/onCompletion (они
            // вызывают remove) срабатывают не синхронно внутри send, а отдельно, плюс remove берёт
            // тот же лок — так что конкурентной модификации списка при итерации не возникает.
            for (final SseEmitter subscriber : subscribers) {
                send(subscriber, event);
            }
            return event;
        } finally {
            lock.unlock();
        }
    }

    public void startRun(String runId) {
        lock.lock();
        try {
            eventLog.clear();
            droppedThroughSeq = 0;
            activeRunId = runId;
        } finally {
            lock.unlock();
        }
    }

    public void endRun(String runId) {
        lock.lock();
        try {
            if (runId.equals(activeRunId)) {
                activeRunId = null;
                eventLog.clear();
                droppedThroughSeq = 0;
            }
        } finally {
            lock.unlock();
        }
    }

    @Nullable
    public String activeRunId() {
        lock.lock();
        try {
            return activeRunId;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Если хаб простаивает (нет подписчиков и активного прогона) — помечает его закрытым и
     * сообщает, что его можно убрать из реестра. После закрытия {@link #subscribe} вернёт {@code
     * null}.
     */
    public boolean closeIfIdle() {
        lock.lock();
        try {
            if (subscribers.isEmpty() && activeRunId == null) {
                closed = true;
                return true;
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Закрывает хаб при остановке приложения: завершает все подписки и больше не принимает новые
     * (как и {@link #closeIfIdle}, но безусловно). Возвращает число закрытых подписчиков.
     *
     * <p>Открытая вкладка — это активный async-запрос: пока {@link SseEmitter} не завершён, Tomcat
     * считает запрос выполняющимся и graceful shutdown ждёт его до своего таймаута (30 с), а потом
     * всё равно обрывает. Поэтому подписки закрываем сами — до старта graceful shutdown (см. {@code
     * run.ChatRuntimeShutdown}).
     */
    public int close() {
        final List<SseEmitter> snapshot;
        lock.lock();
        try {
            if (closed) {
                return 0;
            }
            closed = true;
            snapshot = List.copyOf(subscribers);
            subscribers.clear();
            eventLog.clear();
            droppedThroughSeq = 0;
            activeRunId = null;
        } finally {
            lock.unlock();
        }
        // complete() — вне лока: он дёргает контейнерные колбэки (onCompletion → remove), которым
        // нужен тот же лок, и держать лок на время I/O незачем.
        for (final SseEmitter emitter : snapshot) {
            try {
                emitter.complete();
            } catch (Exception e) {
                log.debug("[{}] complete on shutdown failed: {}", conversationId, e.getMessage());
            }
        }
        log.debug("[{}] hub closed, {} subscriber(s) released", conversationId, snapshot.size());
        return snapshot.size();
    }

    /**
     * Отправляет SSE-комментарий всем подписчикам. При записи в закрытый сокет Spring бросает
     * исключение → onError/onCompletion → remove() → хаб выгружается из реестра. Вызывается по
     * расписанию из {@code run.ChatRuntimeMonitor}.
     */
    public void sendHeartbeat() {
        final List<SseEmitter> snapshot;
        lock.lock();
        try {
            if (subscribers.isEmpty()) return;
            snapshot = new ArrayList<>(subscribers);
        } finally {
            lock.unlock();
        }
        log.debug("[{}] heartbeat to {} subscriber(s)", conversationId, snapshot.size());
        for (final SseEmitter emitter : snapshot) {
            try {
                emitter.send(SseEmitter.event().comment("heartbeat"));
            } catch (Exception e) {
                log.debug(
                        "[{}] heartbeat send failed (dead connection): {}",
                        conversationId,
                        e.getMessage());
                // onError/onCompletion callbacks handle removal
            }
        }
    }

    private void remove(SseEmitter emitter) {
        final boolean idle;
        lock.lock();
        try {
            subscribers.remove(emitter);
            // «Опустел»: последний подписчик ушёл и прогона нет → пора выгружать из реестра.
            idle = subscribers.isEmpty() && activeRunId == null && !closed;
            log.debug(
                    "[{}] subscriber removed, remaining={}, idle={}",
                    conversationId,
                    subscribers.size(),
                    idle);
        } finally {
            lock.unlock();
        }
        // Вне лока: onIdle → closeIfIdle перепроверит состояние под локом (вдруг кто-то успел
        // подписаться), и только тогда хаб закроется и уйдёт из карты.
        if (idle && onIdle != null) {
            log.debug("[{}] calling onIdle", conversationId);
            onIdle.accept(this);
        }
    }

    private void send(SseEmitter emitter, ChatEvent event) {
        try {
            emitter.send(
                    SseEmitter.event()
                            .id(Long.toString(event.seq()))
                            .data(event, MediaType.APPLICATION_JSON));
        } catch (Exception e) {
            // Отвалившийся подписчик уберётся через onError/onCompletion — здесь просто молчим.
            log.debug("[{}] drop on send: {}", conversationId, e.getMessage());
        }
    }
}
