package io.github.trialiya.kb.service;

import io.github.trialiya.kb.model.chat.dto.ChatEvent;
import io.github.trialiya.kb.model.chat.dto.ChatEventType;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
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

    public void startRun(String conversationId, String runId) {
        hub(conversationId).startRun(runId);
    }

    public void endRun(String conversationId, String runId) {
        final ConversationHub hub = hubs.get(conversationId);
        if (hub == null) {
            return;
        }
        hub.endRun(runId);
        // Подчищаем простаивающий хаб, чтобы карта не росла бесконечно. closeIfIdle атомарно
        // (под локом хаба) проверяет «нет подписчиков и прогона» и закрывает хаб — после этого
        // он не примет новых подписчиков, поэтому remove безопасен относительно гонки с subscribe.
        if (hub.closeIfIdle()) {
            hubs.remove(conversationId, hub);
        }
    }

    public Optional<String> activeRunId(String conversationId) {
        return Optional.ofNullable(hubs.get(conversationId)).map(ConversationHub::activeRunId);
    }

    /** Число живых хабов в реестре — для мониторинга утечек (см. ChatRuntimeMonitor). */
    public int hubCount() {
        return hubs.size();
    }

    private ConversationHub hub(String conversationId) {
        return hubs.computeIfAbsent(conversationId, id -> {
            // Массив-держатель нужен, чтобы захватить ссылку на хаб в onIdle-колбэке
            // до завершения его инициализации (иначе circular reference через лямбду).
            final ConversationHub[] ref = new ConversationHub[1];
            ref[0] = new ConversationHub(id, () -> hubs.remove(id, ref[0]));
            return ref[0];
        });
    }
}
