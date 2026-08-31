package io.github.trialiya.kb.service.chat.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import io.github.trialiya.kb.config.ChatClientRegistry;
import io.github.trialiya.kb.config.model.ChatModelProperties;
import io.github.trialiya.kb.config.model.ChatModelProperties.ModelOption;
import io.github.trialiya.kb.config.model.ChatTimeoutProperties;
import io.github.trialiya.kb.model.chat.dto.ChatEventType;
import io.github.trialiya.kb.model.chat.dto.ToolCallsMessage;
import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import io.github.trialiya.kb.model.tool.ToolInvocationMeta;
import io.github.trialiya.kb.service.chat.event.ChatEventService;
import io.github.trialiya.kb.service.chat.memory.AutoCompactService;
import io.github.trialiya.kb.service.chat.memory.ChatHistoryService;
import io.github.trialiya.kb.service.chat.memory.PendingSummaryService;
import io.github.trialiya.kb.service.chat.memory.SummarizeService;
import io.github.trialiya.kb.service.chat.prompt.SystemPromptService;
import io.github.trialiya.kb.service.chat.run.PendingMessageService.Flushed;
import io.github.trialiya.kb.service.chat.runtime.ConversationSlots;
import io.github.trialiya.kb.service.chat.runtime.RunRegistry;
import io.github.trialiya.kb.tools.ToolInvocationCollector.ToolInvocationStatus;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;

/**
 * Что вкладка узнаёт о вызовах инструментов остановленного прогона.
 *
 * <p>Живые {@code TOOL_CALL}-события несут только имя и аргументы вызова; блоки «изменённые файлы»
 * и «изменённые документы» строятся по {@code resultMeta}, которая появляется лишь при записи итога
 * прогона. За успешным прогоном её досылает финальное {@code TOOL_CALLS}-событие — за остановленным
 * она нужна ровно так же, иначе блоки появляются только после перезагрузки страницы.
 */
class ChatRunStopMetaTest {

    private static final String CONV = "conv-1";
    private static final String USER = "admin";

    private ChatHistoryService chatHistory;
    private ChatEventService events;
    private RunRegistry runs;
    private ChatRunService runService;

    @BeforeEach
    void setUp() {
        chatHistory = mock(ChatHistoryService.class);
        when(chatHistory.saveUserMessage(anyString(), anyString(), anyList(), any(), any()))
                .thenAnswer(
                        inv ->
                                new ChatMessageEntity(
                                        1L,
                                        inv.getArgument(0),
                                        inv.getArgument(1),
                                        MessageType.USER,
                                        1,
                                        false,
                                        false,
                                        LocalDateTime.now(),
                                        null));
        when(chatHistory.markRunResult(anyString(), anyString(), any(), any(), anyList()))
                .thenReturn(List.of(editFile()));
        events = spy(new ChatEventService(new ChatTimeoutProperties(Duration.ofMinutes(1))));
        runs = new RunRegistry();
        runService = runService();
    }

    /**
     * Порядок обязателен: {@code RUN_STOPPED} снимает у вкладки метку идущего прогона, а {@code
     * TOOL_CALLS} не от живого прогона она отбрасывает (см. chatEventReducer.js).
     */
    @Test
    void aStoppedRunSendsItsToolMetasBeforeTheTerminalEvent() {
        runService.start(CONV, USER, "привет", List.of(), options(), "msg-1");

        assertThat(runService.stopAll()).isEqualTo(1);

        final ArgumentCaptor<Object> payload = ArgumentCaptor.captor();
        final InOrder order = inOrder(events);
        order.verify(events)
                .publish(
                        eq(CONV),
                        eq(ChatEventType.TOOL_CALLS),
                        anyString(),
                        any(),
                        payload.capture());
        order.verify(events)
                .publish(eq(CONV), eq(ChatEventType.RUN_STOPPED), anyString(), any(), any());
        assertThat(payload.getValue()).isEqualTo(new ToolCallsMessage(List.of(editFile())));
    }

    /** Правка файла: без её {@code resultMeta} блок «изменённые файлы» нарисовать не из чего. */
    private static ToolInvocationMeta editFile() {
        return new ToolInvocationMeta(
                "editFile",
                Map.of("path", "README.md"),
                ToolInvocationStatus.OK,
                null,
                Map.of("path", "README.md", "operation", "edit", "additions", 2, "deletions", 1),
                true,
                0,
                null,
                "call-1");
    }

    /**
     * Прогон, который отдаёт один чанк и дальше висит — как настоящая генерация в момент «Стоп».
     */
    @SuppressWarnings("unchecked")
    private ChatRunService runService() {
        final ChatClient chatClient = mock(ChatClient.class);
        final ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        final ChatClient.StreamResponseSpec stream = mock(ChatClient.StreamResponseSpec.class);
        when(chatClient.prompt()).thenReturn(spec);
        when(spec.system(any(Consumer.class))).thenReturn(spec);
        when(spec.toolContext(any())).thenReturn(spec);
        when(spec.advisors(any(Consumer.class))).thenReturn(spec);
        when(spec.options(any(OpenAiChatOptions.Builder.class))).thenReturn(spec);
        when(spec.stream()).thenReturn(stream);
        when(stream.chatResponse())
                .thenReturn(
                        Flux.concat(
                                Flux.just(
                                        new ChatResponse(
                                                List.of(
                                                        new Generation(
                                                                new AssistantMessage("Привет"))))),
                                Flux.never()));
        final PendingMessageService pendingMessages = mock(PendingMessageService.class);
        when(pendingMessages.flushPlain(anyString())).thenReturn(Flushed.NOTHING);
        return new ChatRunService(
                new ChatClientRegistry("default-model", chatClient, Map.of()),
                mock(ChatMemory.class),
                chatHistory,
                mock(SummarizeService.class),
                mock(PendingSummaryService.class),
                mock(AutoCompactService.class),
                new ChatModelProperties(
                        new ModelOption("default-model", "Default", true, true, null, null, null),
                        List.of()),
                events,
                mock(SystemPromptService.class),
                pendingMessages,
                mock(RunOptionsResolver.class),
                runs,
                new ConversationSlots(events),
                Runnable::run);
    }

    /** Дефолтные настройки прогона: модель/режим/проект не выбраны. */
    private static ChatRunService.RunOptions options() {
        return new ChatRunService.RunOptions(null, false, true, "", null, "kb", null);
    }
}
