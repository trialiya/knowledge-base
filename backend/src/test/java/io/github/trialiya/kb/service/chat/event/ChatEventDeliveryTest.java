package io.github.trialiya.kb.service.chat.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

import io.github.trialiya.kb.config.model.ChatTimeoutProperties;
import io.github.trialiya.kb.model.chat.dto.ChatEventType;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

        final String replay = body(subscribe(0));
        assertThat(replay).contains("пропущенное");
        assertThat(body(subscribe(lastSeq(replay)))).doesNotContain("пропущенное");
    }

    /**
     * Лог реплея не бесконечен, и вкладке, которой выброшенное предназначалось, об этом говорят:
     * иначе она склеила бы куски ответа с дырой посередине, ничего не заметив. Дыру объявляют один
     * раз — курсор вкладки встаёт на последнее выброшенное событие.
     */
    @Test
    void aTabWhoseReplayNoLongerFitsInTheLogIsToldSo() throws Exception {
        events.startRun(CONV, RUN);
        for (int i = 0; i < 2001; i++) {
            events.publish(CONV, ChatEventType.STREAM, RUN, null, "чанк");
        }

        final String replay = body(subscribe(0));
        assertThat(replay).contains("REPLAY_GAP");
        // Хвост лога цел: вкладка, отставшая на одно событие, догоняет без всяких дыр.
        final List<Long> replayed = seqs(replay);
        final long oneBehind = replayed.get(replayed.size() - 2);
        assertThat(body(subscribe(oneBehind))).doesNotContain("REPLAY_GAP").contains("чанк");
        // Тот же факт спрашивают и до подписки — вкладка, которая только грузит чат: начало
        // прогона ей придётся взять из истории (см. GET /runs/active).
        assertThat(events.replayTruncated(CONV)).isTrue();
    }

    /**
     * Курсор из прошлой жизни хаба реплей не отрезает. Хаб живёт меньше вкладки: простаивающий
     * выгружается из реестра, и перезапуск приложения его тем более не переживает, — а номера
     * нового заведомо выше номеров прошлого. Вкладка со старым курсором иначе не получила бы ни
     * одного события уже идущего прогона: все они «старше» её курсора.
     */
    @Test
    void aCursorFromAPreviousHubDoesNotSwallowTheReplay() throws Exception {
        events.startRun(CONV, RUN);
        events.publish(CONV, ChatEventType.COMPACT_STARTED, RUN, null, null);

        // 340 — курсор вкладки, досчитанный на хабе прошлого запуска приложения.
        final String replay = body(subscribe(340));
        assertThat(replay).contains("COMPACT_STARTED");
        // И сразу честное «что из этого вы уже видели — не знаю»: часть реплея вкладка могла
        // применить вживую до обрыва, поэтому историю по концу прогона она перечитает.
        assertThat(replay).contains("REPLAY_GAP");

        // Номер выше всего, что хаб выдавал, курсором не бывает вовсе — и отрезать реплей ему
        // тоже не дают.
        assertThat(body(subscribe(Long.MAX_VALUE))).contains("COMPACT_STARTED");
    }

    /**
     * Тот же курсор прошлой жизни, но попавший <b>ниже</b> текущего номера хаба, — и он тоже не
     * обрезает реплей. Различить его позволяет сквозная нумерация: номера нового хаба заведомо выше
     * всего, что раздали закрывшиеся до него, поэтому «чужой» — это локальная проверка, а не
     * угадывание по верхней границе.
     */
    @Test
    void aCursorFromAPreviousHubBelowTheCurrentSeqDoesNotSwallowTheReplay() throws Exception {
        // Прошлый инстанс хаба этого чата: вкладка досчитала курсор на нём и терминального события
        // не увидела — связь оборвалась, курсор остался.
        final long staleCursor =
                new ConversationHub(CONV, null)
                        .publish(ChatEventType.STREAM, RUN, null, "прошлый прогон")
                        .seq();

        events.startRun(CONV, RUN);
        events.publish(CONV, ChatEventType.COMPACT_STARTED, RUN, null, null);

        final String replay = body(subscribe(staleCursor));
        assertThat(replay).contains("COMPACT_STARTED");
        assertThat(replay).contains("REPLAY_GAP");
    }

    /** Реплеить нечего — и объявлять нечего: пустой лог вкладке ничего не задваивает. */
    @Test
    void aCursorFromAPreviousHubOverAnEmptyLogIsJustIgnored() throws Exception {
        assertThat(body(subscribe(340))).doesNotContain("REPLAY_GAP");
    }

    /** Новый прогон начинается с чистого лога — дыра прошлого на него не переезжает. */
    @Test
    void theGapDoesNotOutliveTheRunThatCausedIt() throws Exception {
        events.startRun(CONV, RUN);
        for (int i = 0; i < 2001; i++) {
            events.publish(CONV, ChatEventType.STREAM, RUN, null, "чанк");
        }
        events.endRun(CONV, RUN);
        events.startRun(CONV, "run-2");

        assertThat(body(subscribe(0))).doesNotContain("REPLAY_GAP");
        assertThat(events.replayTruncated(CONV)).isFalse();
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

    /** Номера событий в теле ответа, по порядку: конкретные значения задаёт сквозной счётчик. */
    private static List<Long> seqs(String body) {
        final Matcher matcher = Pattern.compile("\"seq\":(\\d+)").matcher(body);
        final List<Long> found = new ArrayList<>();
        while (matcher.find()) {
            found.add(Long.parseLong(matcher.group(1)));
        }
        return found;
    }

    private static long lastSeq(String body) {
        final List<Long> found = seqs(body);
        return found.get(found.size() - 1);
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
