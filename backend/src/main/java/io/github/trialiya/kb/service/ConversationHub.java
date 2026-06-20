package io.github.trialiya.kb.service;

import io.github.trialiya.kb.model.chat.dto.ChatEvent;
import io.github.trialiya.kb.model.chat.dto.ChatEventType;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import lombok.extern.slf4j.Slf4j;
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

    private final String conversationId;
    private final ReentrantLock lock = new ReentrantLock();
    private final List<ChatEvent> eventLog = new ArrayList<>();
    private final List<SseEmitter> subscribers = new ArrayList<>();
    private long seq;
    private String activeRunId;
    private boolean closed;

    public ConversationHub(String conversationId) {
        this.conversationId = conversationId;
    }

    /**
     * Подписывает вкладку, сразу реплея пропущенные ею события (seq &gt; {@code fromSeq}).
     * Возвращает {@code null}, если хаб уже закрыт (выгружается из реестра) — вызывающий должен
     * повторить на свежем.
     */
    public SseEmitter subscribe(long fromSeq, long timeoutMillis) {
        final SseEmitter emitter = new SseEmitter(timeoutMillis);
        lock.lock();
        try {
            if (closed) {
                return null;
            }
            for (final ChatEvent event : eventLog) {
                if (event.seq() > fromSeq) {
                    send(emitter, event);
                }
            }
            subscribers.add(emitter);
        } finally {
            lock.unlock();
        }
        emitter.onCompletion(() -> remove(emitter));
        emitter.onTimeout(
                () -> {
                    emitter.complete();
                    remove(emitter);
                });
        emitter.onError(e -> remove(emitter));
        return emitter;
    }

    public ChatEvent publish(ChatEventType type, String runId, String clientMsgId, Object payload) {
        lock.lock();
        try {
            final ChatEvent event = new ChatEvent(++seq, type, runId, clientMsgId, payload);
            eventLog.add(event);
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
            }
        } finally {
            lock.unlock();
        }
    }

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

    private void remove(SseEmitter emitter) {
        lock.lock();
        try {
            subscribers.remove(emitter);
        } finally {
            lock.unlock();
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
