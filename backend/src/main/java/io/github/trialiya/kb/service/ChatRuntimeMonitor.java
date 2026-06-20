package io.github.trialiya.kb.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Временный мониторинг утечек чат-рантайма: периодически печатает в лог размеры in-memory реестров
 * — хабы событий ({@link ChatEventService}), активные прогоны и удержанные заявки на чат ({@link
 * ChatRunService}). Нужен, чтобы убедиться, что hubs/runs корректно закрываются и счётчики в
 * простое возвращаются к нулю.
 *
 * <p>Интервал — {@code kb.chat.monitor-interval-ms} (по умолчанию 60_000); значение {@code <= 0}
 * отключает мониторинг. Свой однопоточный планировщик (а не {@code @Scheduled}) — чтобы не зависеть
 * от {@code @EnableScheduling} и ничего не активировать побочно.
 */
@Slf4j
@Component
public class ChatRuntimeMonitor {

    private final ChatRunService chatRunService;
    private final ChatEventService chatEventService;
    private final long intervalMs;
    private ScheduledExecutorService scheduler;

    public ChatRuntimeMonitor(
            ChatRunService chatRunService,
            ChatEventService chatEventService,
            @Value("${kb.chat.monitor-interval-ms:60000}") long intervalMs) {
        this.chatRunService = chatRunService;
        this.chatEventService = chatEventService;
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
            log.info(
                    "chat runtime registries: eventHubs={}, activeRuns={}, claimedChats={}",
                    chatEventService.hubCount(),
                    chatRunService.activeRunCount(),
                    chatRunService.claimedConversationCount());
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
