package io.github.trialiya.kb.service;

import io.github.trialiya.kb.model.chat.dto.ChatEvent;
import io.github.trialiya.kb.model.chat.dto.ChatEventType;
import java.util.ArrayList;
import java.util.List;
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
 * <p>Публикация и подписка сериализованы одним монитором: это гарантирует, что новый подписчик
 * сначала получит весь пропущенный лог, а затем — живые события, без гонки и пропусков. Отправка
 * идёт под локом; при текущем масштабе (один пользователь, единицы вкладок) это не проблема.
 */
@Slf4j
public class ConversationHub {

    private final String conversationId;
    private final Object lock = new Object();
    private final List<ChatEvent> eventLog = new ArrayList<>();
    private final List<SseEmitter> subscribers = new ArrayList<>();
    private long seq;
    private String activeRunId;

    public ConversationHub(String conversationId) {
        this.conversationId = conversationId;
    }

    /** Подписывает вкладку, сразу реплея пропущенные ею события (seq &gt; {@code fromSeq}). */
    public SseEmitter subscribe(long fromSeq, long timeoutMillis) {
        final SseEmitter emitter = new SseEmitter(timeoutMillis);
        synchronized (lock) {
            for (final ChatEvent event : eventLog) {
                if (event.seq() > fromSeq) {
                    send(emitter, event);
                }
            }
            subscribers.add(emitter);
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
        synchronized (lock) {
            final ChatEvent event = new ChatEvent(++seq, type, runId, clientMsgId, payload);
            eventLog.add(event);
            for (final SseEmitter subscriber : subscribers) {
                send(subscriber, event);
            }
            return event;
        }
    }

    public void startRun(String runId) {
        synchronized (lock) {
            eventLog.clear();
            activeRunId = runId;
        }
    }

    public void endRun(String runId) {
        synchronized (lock) {
            if (runId.equals(activeRunId)) {
                activeRunId = null;
                eventLog.clear();
            }
        }
    }

    public String activeRunId() {
        synchronized (lock) {
            return activeRunId;
        }
    }

    /** Хаб простаивает (можно выгрузить из реестра): нет ни подписчиков, ни активного прогона. */
    public boolean isIdle() {
        synchronized (lock) {
            return subscribers.isEmpty() && activeRunId == null;
        }
    }

    private void remove(SseEmitter emitter) {
        synchronized (lock) {
            subscribers.remove(emitter);
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
