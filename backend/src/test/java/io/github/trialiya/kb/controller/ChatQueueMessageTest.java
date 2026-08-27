package io.github.trialiya.kb.controller;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.trialiya.kb.config.ChatClientRegistry;
import io.github.trialiya.kb.config.model.ChatModeProperties;
import io.github.trialiya.kb.config.model.ChatModelProperties;
import io.github.trialiya.kb.config.model.ChatModelProperties.ModelOption;
import io.github.trialiya.kb.model.chat.dto.StartRunRequest;
import io.github.trialiya.kb.repository.ChatTopicRepository;
import io.github.trialiya.kb.service.chat.context.ContextItemService;
import io.github.trialiya.kb.service.chat.memory.ChatHistoryService;
import io.github.trialiya.kb.service.chat.memory.CompactService;
import io.github.trialiya.kb.service.chat.memory.ToolCallService;
import io.github.trialiya.kb.service.chat.prompt.ProjectPromptService;
import io.github.trialiya.kb.service.chat.prompt.SystemPromptService;
import io.github.trialiya.kb.service.chat.run.ChatEventService;
import io.github.trialiya.kb.service.chat.run.ChatRunService;
import io.github.trialiya.kb.service.chat.run.PendingMessageService;
import io.github.trialiya.kb.service.chat.run.RunOptionsResolver;
import io.github.trialiya.kb.service.chat.script.ScriptGuideService;
import io.github.trialiya.kb.service.chat.topic.ChatSearchService;
import io.github.trialiya.kb.service.chat.topic.ChatTopicService;
import io.github.trialiya.kb.service.file.git.GitRegistry;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * {@code POST /runs/{runId}/messages} — приём сообщения в очередь идущего прогона. Всё, что может
 * отказать, обязано отказать ДО приёма: принятое сообщение пользователь считает отправленным, и
 * отменить этот ответ уже нечем.
 */
class ChatQueueMessageTest {

    private static final String CONV = "conv-1";
    private static final String RUN = "run-1";

    private ChatRunService runService;
    private PendingMessageService pendingMessages;
    private ContextItemService contextItemService;
    private ChatController controller;

    @BeforeEach
    void setUp() {
        runService = mock(ChatRunService.class);
        pendingMessages = mock(PendingMessageService.class);
        contextItemService = mock(ContextItemService.class);
        when(contextItemService.resolve(anyString(), any())).thenReturn(List.of());
        when(runService.isGenerating(CONV, RUN)).thenReturn(true);

        final ChatModelProperties models =
                new ChatModelProperties(
                        new ModelOption("gpt", "GPT", true, true, null, null), List.of());
        controller =
                new ChatController(
                        models,
                        new ChatModeProperties(List.of()),
                        resolver(models),
                        pendingMessages,
                        mock(ChatClientRegistry.class),
                        mock(ChatTopicRepository.class),
                        mock(ChatHistoryService.class),
                        mock(ToolCallService.class),
                        mock(ChatSearchService.class),
                        runService,
                        mock(CompactService.class),
                        mock(ChatEventService.class),
                        mock(ScriptGuideService.class),
                        contextItemService,
                        mock(ChatTopicService.class),
                        mock(GitRegistry.class),
                        mock(SystemPromptService.class),
                        mock(ProjectPromptService.class),
                        Clock.systemUTC());
    }

    /** Выбор запоминается как есть — резолвить его будет уже follow-up прогон. */
    @Test
    void theChosenOptionsAreStoredWithTheMessage() {
        queue("и добавь тесты", "gpt");

        verify(pendingMessages)
                .enqueue(
                        eq(CONV),
                        anyString(),
                        eq("и добавь тесты"),
                        eq(List.of()),
                        eq(new PendingMessageService.PendingOptions("gpt", null, null)),
                        eq(RUN),
                        eq("msg-1"));
    }

    /** Прогон успел кончиться, пока набирали, — фронт повторит обычным {@code POST /runs}. */
    @Test
    void aRunThatIsNoLongerGeneratingIsRejected() {
        when(runService.isGenerating(CONV, RUN)).thenReturn(false);

        assertThatThrownBy(() -> queue("и добавь тесты", null))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);

        verifyNoInteractions(pendingMessages);
    }

    /**
     * Несуществующая модель — отказ на запросе, а не молчание потом: отвечать за принятое сообщение
     * к моменту доставки уже некому.
     */
    @Test
    void anUnknownModelIsRejectedBeforeTheMessageIsAccepted() {
        assertThatThrownBy(() -> queue("и добавь тесты", "gpt-9000"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Unknown model");

        verifyNoInteractions(pendingMessages);
    }

    @Test
    void anEmptyMessageIsRejected() {
        assertThatThrownBy(() -> queue("   ", null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Empty message");

        verifyNoInteractions(pendingMessages);
    }

    /** Чужое вложение — 404 из резолва контекста, и очередь его не видит. */
    @Test
    void aForeignAttachmentIsRejectedBeforeTheMessageIsAccepted() {
        when(contextItemService.resolve(anyString(), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "No such attachment"));

        assertThatThrownBy(() -> queue("смотри лог", null))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        verify(pendingMessages, never())
                .enqueue(anyString(), anyString(), anyString(), any(), any(), anyString(), any());
    }

    private void queue(String text, String model) {
        controller.queueMessage(
                CONV, RUN, model, null, null, "msg-1", new StartRunRequest(text, null));
    }

    private static RunOptionsResolver resolver(ChatModelProperties models) {
        return new RunOptionsResolver(
                models,
                new ChatModeProperties(List.of()),
                mock(io.github.trialiya.kb.service.chat.prompt.ChatModeService.class),
                mock(ChatTopicRepository.class),
                mock(io.github.trialiya.kb.service.file.project.ProjectCatalog.class));
    }
}
