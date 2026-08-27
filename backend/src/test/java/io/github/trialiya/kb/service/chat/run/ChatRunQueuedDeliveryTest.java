package io.github.trialiya.kb.service.chat.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.trialiya.kb.config.ChatClientRegistry;
import io.github.trialiya.kb.config.model.ChatTimeoutProperties;
import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import io.github.trialiya.kb.service.chat.memory.ChatHistoryService;
import io.github.trialiya.kb.service.chat.memory.SummarizeService;
import io.github.trialiya.kb.service.chat.memory.ToolCallEventPublisher;
import io.github.trialiya.kb.service.chat.memory.ToolCallService;
import io.github.trialiya.kb.service.chat.prompt.ProjectPromptService;
import io.github.trialiya.kb.service.chat.prompt.SystemPromptService;
import io.github.trialiya.kb.service.chat.run.PendingMessageService.PendingOptions;
import io.github.trialiya.kb.service.chat.script.ScriptGuideService;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.MessageType;

/**
 * Что происходит с очередью чата, когда прогон кончился. Доставка сюда доезжает во всех случаях,
 * когда advisor-окно так и не наступило: финальный ответ без инструментов, остановка, ошибка.
 *
 * <p>Цена ошибки односторонняя и молчаливая: сообщение, за которое пользователь уже получил
 * «принято», просто не появляется в чате — ни ошибки, ни следа.
 */
class ChatRunQueuedDeliveryTest {

    private static final String CONV = "conv-1";
    private static final String USER = "admin";

    private PendingMessageService pendingMessages;
    private RunOptionsResolver runOptions;
    private ChatHistoryService chatHistory;
    private ChatRunService runService;

    /** Пул, который задачу не исполняет: тест — про решения, а не про саму генерацию. */
    private final Executor never = r -> {};

    @BeforeEach
    void setUp() {
        pendingMessages = mock(PendingMessageService.class);
        runOptions = mock(RunOptionsResolver.class);
        chatHistory = mock(ChatHistoryService.class);
        when(runOptions.resolve(anyString(), any(), any(), any())).thenReturn(options());
        runService =
                new ChatRunService(
                        new ChatClientRegistry("default-model", mock(ChatClient.class), Map.of()),
                        mock(ChatMemory.class),
                        chatHistory,
                        mock(ToolCallService.class),
                        mock(ToolCallEventPublisher.class),
                        mock(SummarizeService.class),
                        new ChatEventService(new ChatTimeoutProperties(Duration.ofMinutes(1))),
                        mock(ScriptGuideService.class),
                        mock(SystemPromptService.class),
                        mock(ProjectPromptService.class),
                        pendingMessages,
                        runOptions,
                        never);
    }

    /**
     * Прогон дошёл до конца — на доставленный вопрос отвечает следующий прогон, и настройки он
     * берёт из очереди: модель и проект могли смениться уже после того, как это сообщение
     * отправили.
     */
    @Test
    void aCompletedRunAnswersWhatTheQueueHeld() {
        when(pendingMessages.flushPlain(CONV))
                .thenReturn(flushed(new PendingOptions("gpt-5", "review", "kb")));
        when(chatHistory.unansweredUserMessage(CONV)).thenReturn(Optional.of(userRow()));

        runService.deliverQueued(CONV, USER, true);

        // Настройки приезжают вместе с доставкой — отдельного «подсмотреть до» нет.
        verify(runOptions).resolve(CONV, "gpt-5", "review", "kb");
        // Путь «Повторить»: нового ряда не заводим — ходом стал доставленный вопрос.
        verify(chatHistory, never()).saveUserMessage(anyString(), anyString(), anyList(), any());
        assertThat(runService.activeRunCount()).isEqualTo(1);
    }

    /**
     * Остановленный и упавший прогоны доставляют, но не отвечают: остановку нажимают, чтобы
     * генерация прекратилась, а ошибка повторилась бы и на следующем прогоне. Сообщение при этом
     * становится последним вопросом истории — тем самым состоянием, где чат предлагает «Повторить».
     */
    @Test
    void anInterruptedRunDeliversWithoutStartingAnother() {
        when(pendingMessages.flushPlain(CONV)).thenReturn(flushed(PendingOptions.NONE));

        runService.deliverQueued(CONV, USER, false);

        verify(pendingMessages).flushPlain(CONV);
        assertThat(runService.activeRunCount()).isZero();
        assertThat(runService.claimedConversationCount()).isZero();
    }

    /** Пустая очередь — самый частый случай: ни прогона, ни резолва настроек. */
    @Test
    void anEmptyQueueStartsNothing() {
        when(pendingMessages.flushPlain(CONV))
                .thenReturn(new PendingMessageService.Flushed(List.of(), PendingOptions.NONE));

        runService.deliverQueued(CONV, USER, true);

        verify(runOptions, never()).resolve(anyString(), any(), any(), any());
        assertThat(runService.activeRunCount()).isZero();
    }

    /**
     * Чат мог занять другая вкладка между освобождением заявки и этим стартом. Сообщение уже в
     * истории, поэтому 409 здесь — не потеря, а повод промолчать: терминальная обработка прогона от
     * этого падать не должна.
     */
    @Test
    void aChatClaimedMeanwhileIsNotAnError() {
        when(pendingMessages.flushPlain(CONV)).thenReturn(flushed(PendingOptions.NONE));
        when(chatHistory.unansweredUserMessage(CONV)).thenReturn(Optional.of(userRow()));
        runService.claim(CONV); // заявку держит кто-то другой

        runService.deliverQueued(CONV, USER, true);

        assertThat(runService.activeRunCount()).isZero();
    }

    /**
     * Страховка на старте прогона — на случай, когда доставить очередь было некому (процесс упал
     * вместе с прогоном). Строго после ремонта хвоста и строго до записи нового вопроса: иначе
     * доставленное встало бы в истории после ответа на него же.
     */
    @Test
    void aStartingRunFlushesLeftoversBeforePersistingItsOwnQuestion() {
        when(chatHistory.saveUserMessage(eq(CONV), anyString(), anyList(), any()))
                .thenReturn(userRow());

        runService.start(CONV, USER, "новый вопрос", List.of(), options(), "msg-1");

        final InOrder order = inOrder(chatHistory, pendingMessages);
        order.verify(chatHistory).repairDanglingToolCalls(CONV);
        order.verify(pendingMessages).flushPlain(CONV);
        order.verify(chatHistory).saveUserMessage(eq(CONV), anyString(), anyList(), any());
    }

    /** Доставлено одно сообщение на названных настройках. */
    private static PendingMessageService.Flushed flushed(PendingOptions options) {
        return new PendingMessageService.Flushed(List.of(userRow()), options);
    }

    private static ChatMessageEntity userRow() {
        return new ChatMessageEntity(
                42L, CONV, "вопрос", MessageType.USER, 1, false, false, LocalDateTime.now(), null);
    }

    /** Дефолтные настройки прогона: модель/режим/проект не выбраны. */
    private static ChatRunService.RunOptions options() {
        return new ChatRunService.RunOptions(null, false, "", null, null);
    }
}
