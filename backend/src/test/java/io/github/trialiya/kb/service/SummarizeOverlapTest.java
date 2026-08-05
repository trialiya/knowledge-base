package io.github.trialiya.kb.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.trialiya.kb.config.model.SummarizeProperties;
import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import io.github.trialiya.kb.repository.ChatMessageRepository;
import io.github.trialiya.kb.service.ChatMemoryService.PromptRow;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * Граница живого хвоста в {@code SummarizeWindow}: правила перекрытия работают в И, а не в ИЛИ —
 * хвост обязан удержать и {@code overlap-messages} сообщений любого рода, и {@code
 * overlap-user-messages} сообщений пользователя, и открыться целым ходом.
 *
 * <p>Цена ошибки — молчаливая и односторонняя: сжатие не падает, а увозит в сводку последние
 * вопросы пользователя, после чего модель отвечает на них по пересказу вместо оригинала.
 *
 * <p>Здесь же закреплена и обратная сторона такой границы: все правила двигают её только назад,
 * поэтому жёсткого потолка над живым окном нет — см. {@code aToolMarathonInsideTheLastFiveTurns*}.
 */
class SummarizeOverlapTest {

    private static final String CONV = "conv-1";

    /**
     * Порог по токенам, до которого срез в этих тестах заведомо не дотягивается: они про то, где
     * проходит граница, а не про то, что запускает раунд. Порог по числу сообщений в них снят до 1,
     * иначе раунд бы вовсе не стартовал.
     */
    private static final int BUDGET = 100_000;

    private ChatMessageRepository repository;
    private ChatMemoryService chatMemoryService;
    private OpenAiChatModel chatModel;

    @BeforeEach
    void setUp() {
        repository = mock(ChatMessageRepository.class);
        chatMemoryService = mock(ChatMemoryService.class);
        chatModel = mock(OpenAiChatModel.class);
        when(chatModel.getOptions()).thenReturn(OpenAiChatOptions.builder().build());
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(
                        new ChatResponse(List.of(new Generation(new AssistantMessage("gist")))));
    }

    /**
     * Правило по числу USER-сообщений сдвигает границу раньше правила по общему числу: последние 20
     * сообщений — один вопрос и длинный хвост ответов, поэтому резать по {@code size - overlap}
     * значило бы оставить живым ровно один вопрос из пяти требуемых.
     */
    @Test
    void userOverlapMovesTheCutoffEarlierThanTheCountOverlap() {
        // 0..39 — чередование вопрос/ответ, 40..59 — только ответы модели.
        final List<ChatMessageEntity> live = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            live.add(message(i, i % 2 == 0 ? MessageType.USER : MessageType.ASSISTANT));
        }
        for (int i = 40; i < 60; i++) {
            live.add(message(i, MessageType.ASSISTANT));
        }
        givenLive(live);

        service(properties(10, 5, 1, BUDGET)).doSummarize(CONV);

        // Правило по числу сообщений дало бы границу 50 → после выравнивания на USER — 38.
        // Пятый с конца вопрос стоит на 30 — он и становится первым живым сообщением.
        verify(repository).updateSummarized(CONV, 0L, 29L);
    }

    /**
     * Вопросов в окне меньше, чем требует {@code overlap-user-messages}: удержать пять там, где
     * есть один, невозможно, поэтому правило отступает и граница берётся по числу сообщений. Иначе
     * один вопрос с бесконечным tool-марафоном навсегда заблокировал бы сжатие.
     */
    @Test
    void tooFewUserMessagesFallBackToTheCountBoundary() {
        final List<ChatMessageEntity> live = new ArrayList<>();
        live.add(message(0, MessageType.USER));
        for (int i = 1; i < 60; i++) {
            live.add(message(i, MessageType.ASSISTANT));
        }
        givenLive(live);

        service(properties(10, 5, 1, BUDGET)).doSummarize(CONV);

        verify(repository).updateSummarized(CONV, 0L, 49L);
    }

    /**
     * Обратная сторона правила: сузив сжимаемый срез, оно может увести его под порог запуска —
     * тогда раунд не стартует вовсе. Это осознанный размен, живой хвост важнее лишнего раунда
     * сжатия.
     */
    @Test
    void userOverlapCanShrinkTheSliceBelowTheThresholds() {
        // Все вопросы — в начале диалога: пятый с конца стоит на позиции 5.
        final List<ChatMessageEntity> live = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            live.add(message(i, MessageType.USER));
        }
        for (int i = 10; i < 60; i++) {
            live.add(message(i, MessageType.ASSISTANT));
        }
        givenLive(live);

        service(properties(10, 5, 50, BUDGET)).doSummarize(CONV);

        verify(repository, never()).updateSummarized(anyString(), anyLong(), anyLong());
        verify(chatModel, never()).call(any(Prompt.class));
    }

    /**
     * Второе правило — потолок, а не замена первому: когда вопросы идут ровно, граница по числу
     * сообщений оказывается раньше и она же побеждает, а хвост всё равно уносит больше
     * USER-сообщений, чем требует {@code overlap-user-messages}.
     */
    @Test
    void countOverlapStillWinsWhenItIsTheEarlierBoundary() {
        // Чередование вопрос/ответ на всём окне: USER стоят на всех чётных индексах.
        final List<ChatMessageEntity> live = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            live.add(message(i, i % 2 == 0 ? MessageType.USER : MessageType.ASSISTANT));
        }
        givenLive(live);

        // Правило по вопросам дало бы границу 54, по числу сообщений — 50; 50 и есть USER.
        service(properties(10, 3, 1, BUDGET)).doSummarize(CONV);

        verify(repository).updateSummarized(CONV, 0L, 49L);
    }

    /**
     * {@code overlap-user-messages: 0} выключает правило целиком и возвращает поведение до его
     * появления — только граница по числу сообщений, выровненная на ближайшее USER-сообщение.
     */
    @Test
    void zeroUserOverlapDisablesTheRule() {
        // То же окно, что и в первом тесте, где правило по вопросам сдвигало границу на 30.
        final List<ChatMessageEntity> live = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            live.add(message(i, i % 2 == 0 ? MessageType.USER : MessageType.ASSISTANT));
        }
        for (int i = 40; i < 60; i++) {
            live.add(message(i, MessageType.ASSISTANT));
        }
        givenLive(live);

        service(properties(10, 0, 1, BUDGET)).doSummarize(CONV);

        // Граница 50 выравнивается вниз до ближайшего USER — это индекс 38.
        verify(repository).updateSummarized(CONV, 0L, 37L);
    }

    /**
     * Заявленная граница политики, а не упущение: tool-марафон внутри последних пяти вопросов
     * остаётся живым целиком. Все три правила хвоста двигают границу только назад, поэтому шесть
     * вопросов с тысячей строк ответов дают срез в одно сообщение — ни порог по числу, ни порог по
     * токенам до него не дотягиваются, ведь оба меряют срез.
     *
     * <p>Окно не заперто навсегда: каждый следующий вопрос сдвигает пятый-с-конца вперёд, и марафон
     * уезжает в срез сам. Пока этого не случилось, живое окно больше {@code token-threshold} — это
     * осознанный размен на предсказуемую границу, а не гарантия, которую здесь дают.
     */
    @Test
    void aToolMarathonInsideTheLastFiveTurnsStaysLive() {
        // 6 вопросов подряд, дальше — только ответы модели. По 500 символов на сообщение:
        // окно 500 000 символов = 125 000 токенов при пороге 30 000.
        final List<ChatMessageEntity> live = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            live.add(message(i, MessageType.USER, 500));
        }
        for (int i = 6; i < 1000; i++) {
            live.add(message(i, MessageType.ASSISTANT, 500));
        }
        givenLive(live);

        // Боевые значения из application.yaml.
        service(new SummarizeProperties(30_000, 50, 30, 5, 5, 4)).doSummarize(CONV);

        // Пятый с конца вопрос стоит на позиции 1 — срез это ровно одно сообщение (~129 токенов).
        verify(repository, never()).updateSummarized(anyString(), anyLong(), anyLong());
    }

    /**
     * Тот же марафон, но диалог пошёл дальше: новые вопросы вытеснили его из хвоста, и он целиком
     * стал срезом. Окно освобождается само по ходу разговора — это и есть ответ на предыдущий тест,
     * из-за него отсутствие жёсткого потолка не означает, что окно растёт вечно.
     */
    @Test
    void theMarathonIsCompressedOnceLaterQuestionsPushItOutOfTheTail() {
        // 0 — вопрос, 1..99 — марафон ответов, дальше 30 сообщений обычного диалога.
        final List<ChatMessageEntity> live = new ArrayList<>();
        live.add(message(0, MessageType.USER));
        for (int i = 1; i < 100; i++) {
            live.add(message(i, MessageType.ASSISTANT));
        }
        for (int i = 100; i < 130; i++) {
            live.add(message(i, i % 2 == 0 ? MessageType.USER : MessageType.ASSISTANT));
        }
        givenLive(live);

        service(new SummarizeProperties(30_000, 50, 30, 5, 5, 4)).doSummarize(CONV);

        // Правило по числу сообщений даёт 130 - 30 = 100, правило по вопросам — 120 (пятый с конца
        // вопрос); побеждает раннее, а 100 и так открывает ход. Весь марафон уходит в сводку.
        verify(repository).updateSummarized(CONV, 0L, 99L);
    }

    /**
     * Тысяча коротких сообщений: срез дорастает до порога по числу сообщений задолго до того, как
     * наберёт токены. Раунд стартует по счётчику — этим порог по числу и полезен, он ловит длинные
     * дешёвые диалоги, которые по токенам ещё не тяжёлые.
     */
    @Test
    void aLongCheapDialogueIsTriggeredByTheMessageCount() {
        final List<ChatMessageEntity> live = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            live.add(message(i, i % 2 == 0 ? MessageType.USER : MessageType.ASSISTANT));
        }
        givenLive(live);

        service(new SummarizeProperties(30_000, 50, 30, 5, 5, 4)).doSummarize(CONV);

        // 1000 - 30 = 970 по числу сообщений; правило по вопросам дало бы 990. Граница 970 — USER.
        verify(repository).updateSummarized(CONV, 0L, 969L);
    }

    /**
     * Короткий диалог: сжимать нечего, и это не должно выглядеть как «пороги не достигнуты». Пять
     * сообщений при {@code overlap-messages: 30} дают отрицательную границу — она обязана
     * прижиматься к нулю, иначе срез уехал бы в отрицательный индекс.
     */
    @Test
    void aShortWindowHasNothingToCompress() {
        final List<PromptRow> live = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            live.add(
                    new PromptRow(
                            message(i, i % 2 == 0 ? MessageType.USER : MessageType.ASSISTANT),
                            "hi"));
        }

        final SummarizeWindow window =
                new SummarizeWindow(live, new SummarizeProperties(30_000, 50, 30, 5, 5, 4));

        assertThat(window.toCompress()).isEmpty();
        assertThat(window.kept()).hasSize(5);
        assertThat(window.worthARound()).isFalse();
    }

    // -------------------------------------------------------------------------

    private void givenLive(List<ChatMessageEntity> live) {
        when(chatMemoryService.promptRows(eq(CONV)))
                .thenReturn(
                        live.stream()
                                .map(entity -> new PromptRow(entity, entity.getContent()))
                                .toList());
    }

    private static ChatMessageEntity message(long position, MessageType type) {
        return message(position, type, 0);
    }

    /** {@code chars} — длина текста: она и есть вес сообщения для токенного бюджета. */
    private static ChatMessageEntity message(long position, MessageType type, int chars) {
        final String text = "message " + position;
        return new ChatMessageEntity(
                position + 1,
                CONV,
                text.length() >= chars ? text : text + "x".repeat(chars - text.length()),
                type,
                position,
                false,
                false,
                LocalDateTime.now(),
                null);
    }

    private static SummarizeProperties properties(
            int overlapMessages,
            int overlapUserMessages,
            int messageThreshold,
            int tokenThreshold) {
        return new SummarizeProperties(
                tokenThreshold, messageThreshold, overlapMessages, overlapUserMessages, 5, 4);
    }

    private SummarizeService service(SummarizeProperties properties) {
        final ContextItemService contextItemService = mock(ContextItemService.class);
        when(contextItemService.render(anyString(), anyList())).thenReturn("");
        return new SummarizeService(
                chatModel,
                repository,
                chatMemoryService,
                new ByteArrayResource("summarize".getBytes()),
                transactionManager(),
                properties,
                contextItemService);
    }

    /** Транзакции здесь ничего не защищают — тест смотрит только на границу среза. */
    private static PlatformTransactionManager transactionManager() {
        final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        final TransactionStatus status = new SimpleTransactionStatus();
        when(manager.getTransaction(any())).thenReturn(status);
        return manager;
    }
}
