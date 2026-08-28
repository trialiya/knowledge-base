package io.github.trialiya.kb.service.chat.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.trialiya.kb.config.model.ChatTimeoutProperties;
import io.github.trialiya.kb.service.chat.event.ChatEventService;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Заявка на чат: одна на все длительные операции с его историей. Цена ошибки здесь — не упавший
 * тест, а сломанная история: две операции, читающие и переписывающие одно окно, оставляют диалог,
 * от которого модель отвечает 400.
 */
class ConversationSlotsTest {

    private static final String CONV = "conv-1";

    private ChatEventService events;
    private ConversationSlots slots;

    @BeforeEach
    void setUp() {
        events = new ChatEventService(new ChatTimeoutProperties(Duration.ofMinutes(1)));
        slots = new ConversationSlots(events);
    }

    /**
     * Занятость не проверяют, а занимают: «свободен» и «занял» — одно атомарное действие. Проверки
     * было бы мало — между ней и первой записью в историю успевает вклиниться чужая операция.
     */
    @Test
    void aBusyChatIsRefusedWith409() {
        slots.take(CONV, "run-1");

        assertThatThrownBy(() -> slots.take(CONV, "run-2"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThatThrownBy(() -> slots.claim(CONV))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(slots.claimedConversationCount()).isEqualTo(1);
    }

    /** Соседний чат ничем не занят — заявка на один разговор, а не на сервис. */
    @Test
    void anotherChatIsNotAffected() {
        slots.take(CONV, "run-1");

        slots.take("conv-2", "run-2");

        assertThat(slots.claimedConversationCount()).isEqualTo(2);
    }

    /**
     * Опоздавшее освобождение снимает только СВОЮ заявку. Иначе {@code cleanup} завершающегося
     * прогона отпускал бы чат, который уже занял следующий, — и в один чат пошли бы две генерации.
     */
    @Test
    void freeingReleasesOnlyItsOwnClaim() {
        slots.take(CONV, "run-1");
        slots.free(CONV, "run-1");
        slots.take(CONV, "run-2");

        slots.free(CONV, "run-1");

        assertThat(slots.claimedConversationCount()).isEqualTo(1);
        assertThatThrownBy(() -> slots.take(CONV, "run-3"))
                .isInstanceOf(ResponseStatusException.class);
    }

    /** {@code claim} держит чат занятым и для вкладок — они видят его прогоном, который нельзя. */
    @Test
    void claimIsVisibleToTabsAndReleaseClearsIt() {
        final String runId = slots.claim(CONV);

        assertThat(slots.activeRun(CONV)).contains(runId);

        slots.release(CONV, runId);

        assertThat(slots.activeRun(CONV)).isEmpty();
        assertThat(slots.claimedConversationCount()).isZero();
    }

    /** Освобождение идемпотентно: повторный вызов ничего не ломает и чужого не трогает. */
    @Test
    void releaseIsIdempotent() {
        final String runId = slots.claim(CONV);
        slots.release(CONV, runId);

        slots.release(CONV, runId);

        assertThat(slots.claimedConversationCount()).isZero();
    }

    /**
     * {@code take} хаба не заводит: вопрос ещё пишется в БД, и показывать вкладкам пока нечего.
     * Отсюда же разница между «занят» для запроса (409) и «занят» для вкладки.
     */
    @Test
    void takeHoldsTheChatWithoutTellingTheTabs() {
        slots.take(CONV, "run-1");

        assertThat(slots.claimedConversationCount()).isEqualTo(1);
        assertThat(slots.activeRun(CONV)).isEmpty();
        assertThat(events.hubCount()).isZero();
    }
}
