package io.github.trialiya.kb.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.trialiya.kb.config.ChatClientRegistry;
import io.github.trialiya.kb.config.model.ChatTimeoutProperties;
import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
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
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Повтор упавшего прогона ({@code POST /runs?retry=true}): ходом остаётся уже сохранённый вопрос, а
 * не его копия. Повторить можно ровно одно состояние — вопрос, на который модель не успела ответить
 * ничем; как только ответ начался, повтор запрещён и диалог продолжается обычным сообщением.
 *
 * <p>Цена ошибки здесь — молчаливая: лишний {@code saveUserMessage} не падает, а тихо задваивает
 * вопрос в истории и в промпте модели.
 */
class ChatRunRetryTest {

    private static final String CONV = "conv-1";
    private static final String USER = "admin";
    private static final String QUESTION = "Привет, модель";

    private ChatMemoryService chatMemoryService;
    private ChatRunService runService;

    /** Пул, который задачу не исполняет: тест — только про решения, принятые в start(). */
    private final Executor never = r -> {};

    @BeforeEach
    void setUp() {
        chatMemoryService = mock(ChatMemoryService.class);
        runService =
                new ChatRunService(
                        new ChatClientRegistry("default-model", mock(ChatClient.class), Map.of()),
                        mock(ChatMemory.class),
                        chatMemoryService,
                        mock(SummarizeService.class),
                        new ChatEventService(new ChatTimeoutProperties(Duration.ofMinutes(1))),
                        mock(ScriptGuideService.class),
                        mock(SystemPromptService.class),
                        never);
    }

    private static ChatMessageEntity userRow(long id) {
        return new ChatMessageEntity(
                id, CONV, QUESTION, MessageType.USER, 1, false, false, LocalDateTime.now(), null);
    }

    @Test
    void retryReusesTheUnansweredQuestionInsteadOfSavingItAgain() {
        when(chatMemoryService.unansweredUserMessage(CONV)).thenReturn(Optional.of(userRow(42L)));

        final ChatRunService.StartedRun started =
                runService.start(CONV, USER, null, List.of(), null, false, "", null);

        assertThat(started.userMessageId()).isEqualTo(42L);
        verify(chatMemoryService, never()).saveUserMessage(anyString(), anyString(), anyList());
    }

    /** Модель успела начать ответ — повторять нечего: 422, и заявка на чат не удерживается. */
    @Test
    void retryIsRejectedOnceTheAnswerHasStarted() {
        when(chatMemoryService.unansweredUserMessage(CONV)).thenReturn(Optional.empty());

        assertThatThrownBy(
                        () -> runService.start(CONV, USER, null, List.of(), null, false, "", null))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);

        assertThat(runService.activeRunCount()).isZero();
        assertThat(runService.claimedConversationCount()).isZero();
    }

    /**
     * Хвост чинится ДО решения о повторе. Оборванный {@code assistant.tool_calls} — это уже начатый
     * ответ, и достроенный ремонтом TOOL-ряд обязан закрыть повтор: в обратном порядке последним
     * рядом ещё был бы вопрос, и прогон запустился бы поверх незакрытой пары.
     */
    @Test
    void repairsDanglingToolCallsBeforeDecidingWhetherRetryIsPossible() {
        when(chatMemoryService.unansweredUserMessage(CONV)).thenReturn(Optional.of(userRow(42L)));

        runService.start(CONV, USER, null, List.of(), null, false, "", null);

        final InOrder order = inOrder(chatMemoryService);
        order.verify(chatMemoryService).repairDanglingToolCalls(CONV);
        order.verify(chatMemoryService).unansweredUserMessage(CONV);
    }

    /** Обычная отправка режим повтора не задевает: вопрос по-прежнему пишется до прогона. */
    @Test
    void ordinarySendStillPersistsTheQuestion() {
        when(chatMemoryService.saveUserMessage(CONV, QUESTION, List.of())).thenReturn(userRow(7L));

        final ChatRunService.StartedRun started =
                runService.start(CONV, USER, QUESTION, List.of(), null, false, "", "msg-1");

        assertThat(started.userMessageId()).isEqualTo(7L);
        verify(chatMemoryService, never()).unansweredUserMessage(anyString());
    }
}
