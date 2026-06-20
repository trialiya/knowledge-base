package io.github.trialiya.kb.service;

import io.github.trialiya.kb.model.chat.dto.ChatEvent;
import io.github.trialiya.kb.model.chat.dto.ChatEventType;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Простой in-memory publish/subscribe поверх {@link ConversationHub}: один хаб на чат, fan-out на
 * все открытые вкладки + replay для переподключения.
 *
 * <p><b>Почему не Spring Integration {@code PublishSubscribeChannel} и не Reactor {@code
 * Sinks}.</b> Ключевое требование — replay: вкладка, открытая или перезагруженная посреди
 * генерации, должна догнать уже сгенерированную часть ответа. Канальные pub/sub-абстракции работают
 * по принципу fire-and-forget и истории не держат, поэтому лог пришлось бы вести вручную в любом
 * случае; при этом добавилась бы динамическая регистрация каналов на каждый чат. Прямой хаб (лог +
 * seq + подписчики) короче и точнее ложится на задачу. Решение рассчитано на один инстанс; для
 * горизонтального масштабирования сюда добавляется Redis Pub/Sub как релей между инстансами,
 * остальное не меняется.
 */
@Slf4j
@Service
public class ChatEventService {

    private static final long TIMEOUT = Duration.ofMinutes(30).toMillis();

    private final ConcurrentHashMap<String, ConversationHub> hubs = new ConcurrentHashMap<>();

    public SseEmitter subscribe(String conversationId, long fromSeq) {
        // Гонка с выгрузкой простаивающего хаба: если хаб успел закрыться между computeIfAbsent и
        // подпиской, subscribe вернёт null — выбрасываем устаревший маппинг и повторяем на свежем.
        while (true) {
            final ConversationHub hub = hub(conversationId);
            final SseEmitter emitter = hub.subscribe(fromSeq, TIMEOUT);
            if (emitter != null) {
                return emitter;
            }
            hubs.remove(conversationId, hub);
        }
    }

    public ChatEvent publish(
            String conversationId,
            ChatEventType type,
            String runId,
            String clientMsgId,
            Object payload) {
        return hub(conversationId).publish(type, runId, clientMsgId, payload);
    }

    /**
     * Публикует событие, только если на чат уже есть хаб (кто-то подписан/был прогон). Не создаёт
     * новый хаб — для уведомлений вроде {@link ChatEventType#CHAT_DELETED}, которые незачем слать,
     * если чат никто не смотрит (иначе плодили бы пустые хабы).
     */
    public void publishIfPresent(
            String conversationId,
            ChatEventType type,
            String runId,
            String clientMsgId,
            Object payload) {
        final ConversationHub hub = hubs.get(conversationId);
        if (hub != null) {
            hub.publish(type, runId, clientMsgId, payload);
        }
    }

    public void startRun(String conversationId, String runId) {
        hub(conversationId).startRun(runId);
    }

    public void endRun(String conversationId, String runId) {
        final ConversationHub hub = hubs.get(conversationId);
        if (hub == null) {
            return;
        }
        hub.endRun(runId);
        // Прогон закончился — хаб мог опустеть (если подписчиков уже нет). Та же логика, что и при
        // уходе последнего подписчика.
        onHubIdle(hub);
    }

    public Optional<String> activeRunId(String conversationId) {
        return Optional.ofNullable(hubs.get(conversationId)).map(ConversationHub::activeRunId);
    }

    /** Число живых хабов в реестре — для мониторинга утечек (см. ChatRuntimeMonitor). */
    public int hubCount() {
        return hubs.size();
    }

    /**
     * Отправляет SSE heartbeat всем подписчикам всех хабов. При записи в закрытый сокет
     * (вкладка закрыта) Spring выбрасывает исключение → onError → remove() → хаб выгружается.
     * Вызывается по расписанию из {@link ChatRuntimeMonitor}.
     */
    public void sendHeartbeats() {
        hubs.values().forEach(ConversationHub::sendHeartbeat);
    }

    /**
     * Хаб сообщил, что простаивает (ушёл последний подписчик). Закрываем и выкидываем из карты;
     * {@code remove(key, value)} снимает ровно этот инстанс, а {@link ConversationHub#closeIfIdle}
     * перепроверяет под локом — если кто-то успел подписаться, хаб останется (вернёт false).
     */
    private void onHubIdle(ConversationHub hub) {
        if (hub.closeIfIdle()) {
            hubs.remove(hub.conversationId(), hub);
            log.info("[{}] hub removed from registry (idle), total={}", hub.conversationId(), hubs.size());
        }
    }

    private ConversationHub hub(String conversationId) {
        return hubs.computeIfAbsent(conversationId, id -> new ConversationHub(id, this::onHubIdle));
    }
}
