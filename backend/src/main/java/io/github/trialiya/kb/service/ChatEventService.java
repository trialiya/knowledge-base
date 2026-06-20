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
        return hub(conversationId).subscribe(fromSeq, TIMEOUT);
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
        hub(conversationId).endRun(runId);
        // Подчищаем простаивающие хабы, чтобы карта не росла бесконечно. Если на хаб прямо сейчас
        // кто-то подписан — он не idle и останется.
        hubs.compute(conversationId, (id, hub) -> hub != null && hub.isIdle() ? null : hub);
    }

    public Optional<String> activeRunId(String conversationId) {
        return Optional.ofNullable(hubs.get(conversationId)).map(ConversationHub::activeRunId);
    }

    private ConversationHub hub(String conversationId) {
        return hubs.computeIfAbsent(conversationId, ConversationHub::new);
    }
}
