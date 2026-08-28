package io.github.trialiya.kb.service.chat.run;

import io.github.trialiya.kb.service.chat.event.ChatEventService;
import io.github.trialiya.kb.service.chat.runtime.ConversationSlots;
import io.github.trialiya.kb.service.chat.runtime.RunRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Временный мониторинг утечек чат-рантайма: периодически печатает в лог размеры in-memory реестров
 * — хабы событий ({@link ChatEventService}), идущие прогоны ({@link RunRegistry}) и удержанные
 * заявки на чат ({@link ConversationSlots}). Нужен, чтобы убедиться, что все они корректно
 * закрываются и счётчики в простое возвращаются к нулю.
 *
 * <p>Интервал — {@code kb.chat.monitor-interval-ms} (по умолчанию 60_000); значение {@code <= 0}
 * отключает мониторинг. Свой однопоточный планировщик (а не {@code @Scheduled}) — чтобы не зависеть
 * от {@code @EnableScheduling} и ничего не активировать побочно.
 */
@Slf4j
@Component
public class ChatRuntimeMonitor {

    private final ChatEventService chatEventService;
    private final RunRegistry runs;
    private final ConversationSlots slots;
    private final long intervalMs;
    @Nullable private ScheduledExecutorService scheduler;

    public ChatRuntimeMonitor(
            ChatEventService chatEventService,
            RunRegistry runs,
            ConversationSlots slots,
            @Value("${kb.chat.monitor-interval-ms:60000}") long intervalMs) {
        this.chatEventService = chatEventService;
        this.runs = runs;
        this.slots = slots;
        this.intervalMs = intervalMs;
    }

    @PostConstruct
    void start() {
        if (intervalMs <= 0) {
            log.info("Chat runtime monitor disabled (kb.chat.monitor-interval-ms={})", intervalMs);
            return;
        }
        scheduler =
                Executors.newSingleThreadScheduledExecutor(
                        runnable -> {
                            final Thread thread = new Thread(runnable, "chat-runtime-monitor");
                            thread.setDaemon(true);
                            return thread;
                        });
        scheduler.scheduleWithFixedDelay(
                this::logSizes, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    private void logSizes() {
        try {
            chatEventService.sendHeartbeats();
            log.info(
                    "chat runtime registries: eventHubs={}, activeRuns={}, claimedChats={}",
                    chatEventService.hubCount(),
                    runs.size(),
                    slots.claimedConversationCount());
        } catch (Exception e) {
            log.warn("Chat runtime monitor failed", e);
        }
    }

    @PreDestroy
    void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }
}
