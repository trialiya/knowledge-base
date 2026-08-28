package io.github.trialiya.kb.advisor;

import static io.github.trialiya.kb.advisor.ToolPreparingAdvisor.RUN_ID_PARAM;
import static io.github.trialiya.kb.model.chat.dto.ChatEventType.RUN_USAGE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.trialiya.kb.model.chat.entity.RunTokenUsage;
import io.github.trialiya.kb.service.chat.run.ChatEventService;
import io.github.trialiya.kb.service.chat.run.RunUsageRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/**
 * Счёт токенов прогона. Advisor — единственное место, где виден usage КАЖДОГО обращения к модели:
 * снаружи tool-цикла чанк с замером итерации отфильтрован, и итог схлопнулся бы до последней.
 *
 * <p>Проверяем ровно то, что три числа {@link RunTokenUsage} считаются каждое по своему правилу и
 * не подменяют друг друга: контекст берётся из последнего обращения, прирост — разностью, выход и
 * оплаченный prompt — суммой.
 */
class TokenUsageAdvisorTest {

    private static final String CONV = "conv-1";
    private static final String RUN = "run-1";

    private final ChatEventService events = mock(ChatEventService.class);
    private final RunUsageRegistry registry = new RunUsageRegistry();
    private final StreamAdvisorChain chain = mock(StreamAdvisorChain.class);

    private final TokenUsageAdvisor advisor = new TokenUsageAdvisor(events, registry);

    /**
     * Ответ с инструментами: prompt второго обращения включает первое целиком, поэтому контекст —
     * это последнее обращение, а не сумма (она была бы 500 при реально занятых 430).
     */
    @Test
    void contextComesFromTheLastCallAndGrowthFromTheDifference() {
        registry.start(RUN);
        when(chain.nextStream(any()))
                .thenReturn(Flux.just(chunk(100, 10)))
                .thenReturn(Flux.just(chunk(400, 30)));

        advisor.adviseStream(request(), chain).blockLast();
        advisor.adviseStream(request(), chain).blockLast();

        assertThat(registry.total(RUN)).isEqualTo(usage(430, 300, 40, 500, 2));
    }

    /** Ответ без инструментов: наращивать контекст было нечем, прирост нулевой. */
    @Test
    void aRunWithoutToolsGrowsTheContextByNothing() {
        registry.start(RUN);
        when(chain.nextStream(any())).thenReturn(Flux.just(chunk(1000, 50)));

        advisor.adviseStream(request(), chain).blockLast();

        assertThat(registry.total(RUN)).isEqualTo(usage(1050, 0, 50, 1000, 1));
    }

    /** Нарастающий итог внутри одного обращения складывать нельзя — только брать максимум. */
    @Test
    void aRunningTotalWithinOneCallIsNotSummed() {
        registry.start(RUN);
        when(chain.nextStream(any()))
                .thenReturn(Flux.just(chunk(100, 5), chunk(100, 20), chunk(100, 33)));

        advisor.adviseStream(request(), chain).blockLast();

        assertThat(registry.total(RUN)).isEqualTo(usage(133, 0, 33, 100, 1));
    }

    /**
     * Чанк с одним лишь выходом (провайдер вправе прислать такой по ходу обращения) не становится
     * ни первым, ни последним обращением: иначе контекст обвалился бы до размера этого чанка.
     */
    @Test
    void aChunkWithoutAPromptDoesNotCollapseTheContext() {
        registry.start(RUN);
        when(chain.nextStream(any()))
                .thenReturn(Flux.just(chunk(100, 10)))
                .thenReturn(Flux.just(chunk(0, 7), chunk(400, 30)));

        advisor.adviseStream(request(), chain).blockLast();
        advisor.adviseStream(request(), chain).blockLast();

        assertThat(publishedUsage()).extracting(RunTokenUsage::contextTokens).isSorted();
        assertThat(registry.total(RUN)).isEqualTo(usage(430, 300, 40, 500, 2));
    }

    /** Каждый непустой замер уезжает на фронт, и в событии — состояние всего прогона. */
    @Test
    void eachNonEmptyMeasurementIsPublishedAsTheRunState() {
        registry.start(RUN);
        when(chain.nextStream(any()))
                .thenReturn(Flux.just(chunk(100, 5), chunk(100, 20)))
                .thenReturn(Flux.just(chunk(400, 30)));

        advisor.adviseStream(request(), chain).blockLast();
        advisor.adviseStream(request(), chain).blockLast();

        assertThat(publishedUsage())
                .containsExactly(
                        usage(105, 0, 5, 100, 1),
                        usage(120, 0, 20, 100, 1),
                        usage(430, 300, 50, 500, 2));
    }

    /**
     * Пока не измерен ни один prompt, показывать нечего: заполнение контекста — заголовочное число
     * плашки, и события до него не публикуются вовсе.
     */
    @Test
    void aMeasurementWithoutAContextIsNotPublished() {
        registry.start(RUN);
        when(chain.nextStream(any()))
                .thenReturn(Flux.just(chunk(0, 7), chunk(0, 12), chunk(400, 30)));

        advisor.adviseStream(request(), chain).blockLast();

        assertThat(publishedUsage()).containsExactly(usage(430, 0, 30, 400, 1));
    }

    /** Повтор того же замера событие не порождает: у провайдера с финальным чанком оно одно. */
    @Test
    void anUnchangedMeasurementIsNotRepublished() {
        registry.start(RUN);
        when(chain.nextStream(any()))
                .thenReturn(Flux.just(chunk(100, 20), chunk(100, 20), chunk(100, 20)));

        advisor.adviseStream(request(), chain).blockLast();

        assertThat(publishedUsage()).containsExactly(usage(120, 0, 20, 100, 1));
    }

    /** Эндпоинт без usage в стриме: событий нет вовсе — «неизвестно» это не ноль. */
    @Test
    void anEndpointWithoutUsageProducesNoEvents() {
        registry.start(RUN);
        when(chain.nextStream(any())).thenReturn(Flux.just(chunk(0, 0), chunkWithoutMetadata()));

        advisor.adviseStream(request(), chain).blockLast();

        verifyNoInteractions(events);
        assertThat(registry.total(RUN)).isEqualTo(RunTokenUsage.EMPTY);
    }

    /** Сжатие контекста, суб-агент и генерация названия идут мимо прогона — их не считаем. */
    @Test
    void aCallOutsideAnyRunIsLeftAlone() {
        when(chain.nextStream(any())).thenReturn(Flux.just(chunk(100, 10)));

        advisor.adviseStream(request(), chain).blockLast();

        verifyNoInteractions(events);
        assertThat(registry.trackedRunCount()).isZero();
    }

    /** Прогон остановили посреди обращения — потраченное к этому моменту потрачено. */
    @Test
    void aCancelledCallStillCounts() {
        registry.start(RUN);
        when(chain.nextStream(any()))
                .thenReturn(Flux.just(chunk(100, 10)).concatWith(Flux.never()));

        advisor.adviseStream(request(), chain).take(1).blockLast();

        assertThat(registry.total(RUN)).isEqualTo(usage(110, 0, 10, 100, 1));
    }

    /** Ожидаемый итог прогона; кэш во всех сценариях здесь нулевой. */
    private static RunTokenUsage usage(
            long context, long tools, long output, long prompt, int calls) {
        return new RunTokenUsage(context, tools, output, prompt, 0, 0, calls);
    }

    /** Замеры, доехавшие до фронта, по порядку. */
    private List<RunTokenUsage> publishedUsage() {
        final ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(events, atLeastOnce())
                .publish(eq(CONV), eq(RUN_USAGE), eq(RUN), isNull(), payload.capture());
        return payload.getAllValues().stream().map(RunTokenUsage.class::cast).toList();
    }

    private static ChatClientRequest request() {
        return ChatClientRequest.builder()
                .prompt(new Prompt(new UserMessage("привет")))
                .context(Map.of(ChatMemory.CONVERSATION_ID, CONV, RUN_ID_PARAM, RUN))
                .build();
    }

    private static ChatClientResponse chunk(int prompt, int completion) {
        return ChatClientResponse.builder()
                .chatResponse(
                        new ChatResponse(
                                List.of(new Generation(new AssistantMessage("…"))),
                                ChatResponseMetadata.builder()
                                        .usage(new DefaultUsage(prompt, completion))
                                        .build()))
                .build();
    }

    private static ChatClientResponse chunkWithoutMetadata() {
        return ChatClientResponse.builder()
                .chatResponse(new ChatResponse(List.of(new Generation(new AssistantMessage("…")))))
                .build();
    }
}
