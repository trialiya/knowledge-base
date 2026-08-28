package io.github.trialiya.kb.service.chat.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
import io.github.trialiya.kb.service.chat.run.PendingMessageService.Flushed;
import io.github.trialiya.kb.service.chat.run.PendingMessageService.PendingOptions;
import io.github.trialiya.kb.service.chat.script.ScriptGuideService;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.MessageType;

/**
 * Что происходит с очередью чата, когда прогон кончился. Сюда доезжает всё, что advisor не успел
 * забрать: финальный ответ без инструментов, остановка, ошибка, падение процесса.
 *
 * <p>Цена ошибки односторонняя и молчаливая: сообщение, за которое пользователь уже получил
 * «принято», просто не появляется в чате — ни ошибки, ни следа.
 */
class ChatRunQueuedDeliveryTest {

    private static final String CONV = "conv-1";
    private static final String USER = "admin";
    private static final String RUN = "run-1";

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
        when(pendingMessages.flushPlain(anyString())).thenReturn(Flushed.NOTHING);
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
                        new RunUsageRegistry(),
                        never);
    }

    /**
     * Прогон кончился между проверкой на приёме и коммитом строки очереди: его собственная доставка
     * застала очередь пустой, а второй у него не будет. Перепроверка на приёме закрывает это окно.
     *
     * <p>Отвечать она при этом не начинает: чем кончился тот прогон, отсюда уже не видно — в
     * реестре его нет, — а за остановленным и упавшим ответа быть не должно. Сообщение становится
     * последним вопросом истории, то есть попадает ровно туда, где чат предлагает «Повторить».
     */
    @Test
    void aMessageAcceptedAsTheRunEndedIsDeliveredButNotAnswered() {
        when(pendingMessages.flushPlain(CONV))
                .thenReturn(flushed(new PendingOptions("gpt-5", "review", "kb")));

        runService.deliverIfNobodyGenerates(CONV);

        verify(pendingMessages).flushPlain(CONV);
        verify(runOptions, never()).resolve(anyString(), any(), any(), any());
        verify(chatHistory, never()).saveUserMessage(anyString(), anyString(), anyList(), any());
        assertThat(runService.activeRunCount()).isZero();
    }

    /**
     * В чате генерирует прогон — и неважно, тот ли, к которому сообщение вставало в очередь: за
     * окно между проверкой и коммитом другая вкладка успевает начать следующий. Доставить обычным
     * вопросом сейчас — значит вписать USER-ряд в середину чужого хода, между {@code
     * assistant.tool_calls} и ответами инструментов. Очередь остаётся живому прогону.
     */
    @Test
    void aChatWithAnyLiveRunIsLeftAlone() {
        when(chatHistory.saveUserMessage(eq(CONV), anyString(), anyList(), any()))
                .thenReturn(userRow());
        runService.start(CONV, USER, "вопрос", List.of(), options(), null);

        runService.deliverIfNobodyGenerates(CONV);

        // Один flushPlain — страховочный, на старте прогона; второго тут быть не должно.
        verify(pendingMessages, times(1)).flushPlain(CONV);
    }

    /** Пустая очередь — самый частый случай: ни прогона, ни резолва настроек. */
    @Test
    void anEmptyQueueStartsNothing() {
        runService.deliverIfNobodyGenerates(CONV);

        verify(runOptions, never()).resolve(anyString(), any(), any(), any());
        assertThat(runService.activeRunCount()).isZero();
    }

    /** Сорвавшаяся доставка вызывающего не роняет: сообщение ждёт в очереди следующего повода. */
    @Test
    void aFailingDeliveryIsSwallowed() {
        when(pendingMessages.flushPlain(CONV)).thenThrow(new IllegalStateException("boom"));

        runService.deliverIfNobodyGenerates(CONV);

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
    private static Flushed flushed(PendingOptions options) {
        return new Flushed(List.of(userRow()), USER, options);
    }

    private static ChatMessageEntity userRow() {
        return new ChatMessageEntity(
                42L, CONV, "вопрос", MessageType.USER, 1, false, false, LocalDateTime.now(), null);
    }

    /** Дефолтные настройки прогона: модель/режим/проект не выбраны. */
    private static ChatRunService.RunOptions options() {
        return new ChatRunService.RunOptions(null, false, true, "", null, null);
    }
}
