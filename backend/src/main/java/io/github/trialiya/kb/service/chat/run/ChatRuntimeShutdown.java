package io.github.trialiya.kb.service.chat.run;

import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Гасит чат-рантайм при остановке приложения.
 *
 * <p><b>Зачем.</b> Подписка вкладки на {@code GET /events} — это долгоживущий async-запрос: пока
 * {@code SseEmitter} не завершён, Tomcat считает запрос активным. Начиная с Spring Boot 4 graceful
 * shutdown включён по умолчанию, поэтому одна открытая вкладка задерживала остановку на все 30 с
 * ({@code spring.lifecycle.timeout-per-shutdown-phase}), после чего соединение всё равно
 * обрывалось:
 *
 * <pre>
 * Commencing graceful shutdown. Waiting for active requests to complete
 * ... phase 2147482623 ends with 1 bean still running after timeout of 30000ms: [webServerGracefulShutdown]
 * Graceful shutdown aborted with one or more requests still active
 * </pre>
 *
 * <p><b>Порядок важен.</b> {@link ContextClosedEvent} публикуется ДО остановки Lifecycle-бинов, то
 * есть до старта graceful shutdown, — это последний момент, когда можно закрыть подписки штатно.
 * Сначала останавливаем прогоны (dispose → CANCEL → частичное сохранение с пометкой {@code
 * [stopped]} → событие RUN_STOPPED уходит ещё живым подписчикам) и ждём их завершения, потому что
 * терминальная обработка пишет в БД, а пул соединений закроется сразу после shutdown. Только затем
 * закрываем сами подписки: вкладки увидят обрыв и переподключатся с backoff уже к поднявшемуся
 * инстансу, дозагрузив пропущенное по {@code fromSeq}.
 *
 * <p>Ожидание ограничено {@code kb.chat.shutdown-grace-ms} (по умолчанию 5000): даже если прогон
 * завис в инструменте, остановка не превращается обратно в минуты ожидания.
 */
@Slf4j
@Component
public class ChatRuntimeShutdown {

    private final ChatRunService chatRunService;
    private final ChatEventService chatEventService;
    private final Duration grace;

    public ChatRuntimeShutdown(
            ChatRunService chatRunService,
            ChatEventService chatEventService,
            @Value("${kb.chat.shutdown-grace-ms:5000}") long graceMs) {
        this.chatRunService = chatRunService;
        this.chatEventService = chatEventService;
        this.grace = Duration.ofMillis(Math.max(graceMs, 0));
    }

    @EventListener
    public void onContextClosed(ContextClosedEvent event) {
        final int runs = chatRunService.stopAll();
        if (runs > 0 && !chatRunService.awaitQuiescence(grace)) {
            log.warn(
                    "Chat runs did not finish within {} ms — partial replies may be lost",
                    grace.toMillis());
        }
        final int subscribers = chatEventService.closeAll();
        if (runs > 0 || subscribers > 0) {
            log.info(
                    "Chat runtime stopped: runs cancelled={}, SSE subscriptions closed={}",
                    runs,
                    subscribers);
        }
    }
}
