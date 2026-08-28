package io.github.trialiya.kb.service.chat.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

import io.github.trialiya.kb.config.model.ChatTimeoutProperties;
import io.github.trialiya.kb.model.chat.dto.ChatEventType;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Событие доходит до вкладки. Проверяется через настоящий SSE-запрос: {@link SseEmitter} пишет в
 * ответ только после того, как контейнер начал асинхронную обработку, поэтому голый эмиттер из
 * {@code subscribe} в тесте ничего не отдаёт и доставку по нему не увидеть. Отсюда MockMvc поверх
 * подставного контроллера — своего эндпоинта {@link ChatEventService} не имеет, а настоящий тянет
 * за собой весь {@code ChatController}.
 *
 * <p>Соседний {@code ChatEventServiceTest} проверяет обратное — что публикация хаба не заводит; без
 * этого теста то свойство было бы неотличимо от «публикация не делает вообще ничего».
 */
class ChatEventDeliveryTest {

    private static final String CONV = "conv-1";
    private static final String RUN = "run-1";

    private ChatEventService events;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        events = new ChatEventService(new ChatTimeoutProperties(Duration.ofMinutes(1)));
        mockMvc = MockMvcBuilders.standaloneSetup(new TestEvents(events)).build();
    }

    @Test
    void anEventReachesAnOpenTab() throws Exception {
        final MvcResult subscription = subscribe(0);

        events.publish(CONV, ChatEventType.STREAM, RUN, null, "первый токен");

        assertThat(body(subscription)).contains("STREAM").contains("первый токен");
    }

    /**
     * Вкладка, переподключившаяся посреди прогона, догоняет пропущенное: лог хаба реплеится с
     * {@code fromSeq}, а не начинается с текущего момента.
     */
    @Test
    void aReconnectingTabCatchesUpOnWhatItMissed() throws Exception {
        events.startRun(CONV, RUN);
        events.publish(CONV, ChatEventType.STREAM, RUN, null, "пропущенное");

        assertThat(body(subscribe(0))).contains("пропущенное");
        assertThat(body(subscribe(1))).doesNotContain("пропущенное");
    }

    /**
     * Замер, доехавший после конца прогона, в открытую вкладку всё-таки уходит: хаб держит её
     * подписка, и терять событие не из-за чего. Прогон при этом не воскресает — {@code activeRunId}
     * остаётся пустым, и вкладка не покажет чат занятым.
     */
    @Test
    void aLateEventStillReachesAnOpenTabWithoutRevivingTheRun() throws Exception {
        final MvcResult subscription = subscribe(0);
        events.startRun(CONV, RUN);
        events.endRun(CONV, RUN);

        events.publish(CONV, ChatEventType.RUN_USAGE, RUN, null, "поздний замер");

        assertThat(body(subscription)).contains("поздний замер");
        assertThat(events.activeRunId(CONV)).isEmpty();
    }

    private MvcResult subscribe(long fromSeq) throws Exception {
        return mockMvc.perform(get("/test-events").param("fromSeq", String.valueOf(fromSeq)))
                .andExpect(request().asyncStarted())
                .andReturn();
    }

    /**
     * Тело ответа как UTF-8: у {@code MockHttpServletResponse} без явной кодировки по умолчанию
     * ISO-8859-1, и кириллица в payload читалась бы мусором.
     */
    private static String body(MvcResult result) {
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    @RestController
    private static final class TestEvents {

        private final ChatEventService events;

        private TestEvents(ChatEventService events) {
            this.events = events;
        }

        @GetMapping("/test-events")
        SseEmitter events(@RequestParam long fromSeq) {
            return events.subscribe(CONV, fromSeq);
        }
    }
}
