package io.github.trialiya.kb.service.chat.event;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trialiya.kb.config.model.ChatTimeoutProperties;
import io.github.trialiya.kb.model.chat.dto.ChatEventType;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Кто заводит хаб чата. Ответ ровно один — подписка вкладки и начало прогона; всё остальное только
 * пишет в уже существующий. Хаб, заведённый публикацией, закрыть было бы некому: закрывают его уход
 * последнего подписчика и конец прогона, а у такого хаба нет ни того, ни другого.
 */
class ChatEventServiceTest {

    private static final String CONV = "conv-1";
    private static final String RUN = "run-1";

    private final ChatEventService events =
            new ChatEventService(new ChatTimeoutProperties(Duration.ofMinutes(1)));

    @Test
    void aHubComesFromASubscriptionOrFromAStartedRun() {
        events.subscribe(CONV, 0);
        assertThat(events.hubCount()).isEqualTo(1);

        events.startRun("conv-2", RUN);

        assertThat(events.hubCount()).isEqualTo(2);
        assertThat(events.activeRunId("conv-2")).contains(RUN);
    }

    /** Уведомление в чат, который никто не смотрит, — некому и незачем. */
    @Test
    void anEventForANobodysChatIsDropped() {
        events.publish(CONV, ChatEventType.CHAT_DELETED, null, null, null);

        assertThat(events.hubCount()).isZero();
    }

    /**
     * Главное здесь. Опоздавшее событие прогона (замер последнего чанка, запись истории из
     * tool-цикла) приходит уже после {@code endRun} — и хаб заново не поднимает: закрыть его было
     * бы некому, и он висел бы в реестре с протухшим событием в логе до следующего касания чата.
     */
    @Test
    void anEventArrivingAfterTheRunEndedResurrectsNothing() {
        events.startRun(CONV, RUN);
        events.endRun(CONV, RUN);
        assertThat(events.hubCount()).isZero();

        events.publish(CONV, ChatEventType.RUN_USAGE, RUN, null, "поздний замер");

        assertThat(events.hubCount()).isZero();
        assertThat(events.activeRunId(CONV)).isEmpty();
    }

    /** Хаб с подписчиком переживает конец прогона: вкладка осталась, слать ей ещё есть что. */
    @Test
    void aSubscribedHubOutlivesTheRun() {
        events.subscribe(CONV, 0);
        events.startRun(CONV, RUN);

        events.endRun(CONV, RUN);

        assertThat(events.hubCount()).isEqualTo(1);
        assertThat(events.activeRunId(CONV)).isEmpty();
    }
}
