package io.github.trialiya.kb.service.chat.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.trialiya.kb.config.CommonConfig;
import io.github.trialiya.kb.config.model.ChatTimeoutProperties;
import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import io.github.trialiya.kb.repository.ChatMessageRepository;
import io.github.trialiya.kb.repository.ToolCallIndexRepository;
import io.github.trialiya.kb.service.chat.context.AttachmentService;
import io.github.trialiya.kb.service.chat.context.ContextItemService;
import io.github.trialiya.kb.service.chat.event.ChatEventService;
import io.github.trialiya.kb.service.chat.runtime.RunRegistry;
import io.github.trialiya.kb.support.ActiveProjectNotices;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jdbc.test.autoconfigure.DataJdbcTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;

/**
 * Пользовательское сообщение сохраняется ДО обращения к модели ({@code ChatRunService.start}), а
 * сам прогон запускается БЕЗ {@code .user(...)} — историю подмешивает advisor памяти.
 *
 * <p>Схема держится на одном свойстве Spring AI: {@code MessageChatMemoryAdvisor.before()} в конце
 * берёт {@code prompt.getLastUserOrToolResponseMessage()} и отдаёт его в {@code chatMemory.add}, а
 * {@link ChatHistoryService#append} выбрасывает всё, что пришло уже сохранённым {@code IMessage}.
 * Если своего user-сообщения в промпте нет, последним оказывается наш предсохранённый ряд — и
 * повторно он не пишется. Тесты пиннят именно это: сломается версия Spring AI — упадёт здесь, а не
 * дублями в проде.
 *
 * <p>Здесь же — окно повтора ({@link ChatHistoryService#unansweredUserMessage}): тот же ряд служит
 * ходом при повторе упавшего прогона, но только пока модель не ответила ничем.
 */
@ActiveProfiles("h2")
@DataJdbcTest(
        properties = {
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.url=jdbc:h2:mem:kb-prepersist-test;MODE=PostgreSQL;"
                    + "DEFAULT_NULL_ORDERING=HIGH;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            "spring.flyway.locations=classpath:db/migration-h2",
            "spring.data.jdbc.dialect=postgresql",
        })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import(CommonConfig.class)
// Без откатываемой транзакции теста: в стриминге BaseAdvisor.adviseStream выполняет before()
// через publishOn(scheduler), то есть на ДРУГОМ потоке и другом соединении. Незакоммиченную
// строку он бы не увидел — как не увидит её и прод, если сохранить сообщение в незакрытой
// транзакции. Каждый тест работает в своём conversationId, поэтому чистка не нужна.
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PrePersistedUserMessageTest {

    private static final String QUESTION = "Привет, модель";
    private static final String REPLY = "Ответ";

    @Autowired private ChatMessageRepository messageRepo;
    @Autowired private ToolCallIndexRepository toolCallIndexRepo;

    private ChatHistoryService memoryService() {
        return new ChatHistoryService(
                messageRepo,
                new ContextItemService(mock(AttachmentService.class)),
                new ToolCallService(messageRepo, toolCallIndexRepo),
                new ToolCallEventPublisher(
                        new ChatEventService(new ChatTimeoutProperties(Duration.ofMinutes(1))),
                        new RunRegistry()),
                ActiveProjectNotices.silent());
    }

    private static ChatModel stubModel() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.getOptions()).thenReturn(OpenAiChatOptions.builder().build());
        ChatResponse response =
                new ChatResponse(List.of(new Generation(new AssistantMessage(REPLY))));
        when(chatModel.call(any(Prompt.class))).thenReturn(response);
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(response));
        return chatModel;
    }

    /**
     * Цепочка advisor-ов ровно как в {@code ChatConfig#chatClient}: tool-цикл снаружи, память
     * внутри него. Проверять предсохранение на голом advisor памяти недостаточно — в проде {@code
     * before()} вызывается на каждой итерации tool-цикла.
     */
    private ChatClient chatClient(
            ChatModel model, ChatHistoryService memory, boolean withToolLoop) {
        ChatMemory chatMemory = new ChatHistoryMemory(memory);
        List<Advisor> advisors = new java.util.ArrayList<>();
        if (withToolLoop) {
            advisors.add(
                    ToolCallingAdvisor.builder()
                            .toolCallingManager(ToolCallingManager.builder().build())
                            .disableInternalConversationHistory()
                            .build());
            advisors.add(
                    MessageChatMemoryAdvisor.builder(chatMemory)
                            .order(ToolCallingAdvisor.DEFAULT_ORDER + 100)
                            .build());
        } else {
            advisors.add(MessageChatMemoryAdvisor.builder(chatMemory).build());
        }
        return ChatClient.builder(model).defaultAdvisors(advisors).build();
    }

    /** Записывает USER-строку напрямую — так это будет делать {@code ChatRunService.start}. */
    private long prePersistUser(String conversationId, String text) {
        long nextPosition =
                messageRepo
                                .findFirstByConversationIdOrderByPositionDesc(conversationId)
                                .map(ChatMessageEntity::getPosition)
                                .orElse(0L)
                        + 1;
        return messageRepo
                .save(
                        new ChatMessageEntity(
                                0,
                                conversationId,
                                text,
                                MessageType.USER,
                                nextPosition,
                                false,
                                false,
                                LocalDateTime.now(),
                                null))
                .getId();
    }

    private List<ChatMessageEntity> userRows(String conversationId) {
        return messageRepo
                .findChatMessageByConversationIdAndSummaryFalseOrderByCreatedAtAscPositionAsc(
                        conversationId)
                .stream()
                .filter(m -> m.getType() == MessageType.USER)
                .toList();
    }

    @Test
    void prePersistedUserMessageReachesModelAndIsNotDuplicated() {
        String conversationId = UUID.randomUUID().toString();
        ChatHistoryService memory = memoryService();
        ChatModel model = stubModel();

        long userMessageId = prePersistUser(conversationId, QUESTION);

        String reply =
                chatClient(model, memory, false)
                        .prompt()
                        .system("Системный промпт")
                        .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                        .call()
                        .content();

        assertThat(reply).isEqualTo(REPLY);

        // Текст пользователя дошёл до модели ровно один раз, хотя .user(...) не вызывался:
        // сообщение подмешал advisor памяти, прочитав его из истории.
        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(promptCaptor.capture());
        assertThat(promptCaptor.getValue().getInstructions())
                .filteredOn(m -> QUESTION.equals(m.getText()))
                .hasSize(1);

        // Дубля нет: USER-строка ровно одна, та же самая.
        assertThat(userRows(conversationId))
                .singleElement()
                .satisfies(m -> assertThat(m.getId()).isEqualTo(userMessageId));

        assertThat(memory.promptMessages(conversationId)).anyMatch(m -> REPLY.equals(m.getText()));
    }

    /** Боевой путь: {@code stream()} и цепочка с tool-циклом, как в {@code ChatConfig}. */
    @Test
    void prePersistedUserMessageSurvivesStreamingWithToolLoop() {
        String conversationId = UUID.randomUUID().toString();
        ChatHistoryService memory = memoryService();
        ChatModel model = stubModel();

        long userMessageId = prePersistUser(conversationId, QUESTION);

        List<ChatResponse> received =
                chatClient(model, memory, true)
                        .prompt()
                        .system("Системный промпт")
                        .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                        .stream()
                        .chatResponse()
                        .collectList()
                        .block(Duration.ofSeconds(10));

        assertThat(received).isNotNull().isNotEmpty();

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(model).stream(promptCaptor.capture());
        assertThat(promptCaptor.getValue().getInstructions())
                .filteredOn(m -> QUESTION.equals(m.getText()))
                .hasSize(1);

        assertThat(userRows(conversationId))
                .singleElement()
                .satisfies(m -> assertThat(m.getId()).isEqualTo(userMessageId));
    }

    @Test
    void prePersistedUserMessageSurvivesStreamingWithoutToolLoop() {
        String conversationId = UUID.randomUUID().toString();
        ChatHistoryService memory = memoryService();
        ChatModel model = stubModel();

        prePersistUser(conversationId, QUESTION);

        chatClient(model, memory, false)
                .prompt()
                .system("Системный промпт")
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .stream()
                .chatResponse()
                .collectList()
                .block(Duration.ofSeconds(10));

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(model).stream(promptCaptor.capture());
        assertThat(promptCaptor.getValue().getInstructions())
                .filteredOn(m -> QUESTION.equals(m.getText()))
                .hasSize(1);
    }

    /**
     * Тот же предсохранённый ряд служит ходом и на повторе упавшего прогона — {@link
     * ChatHistoryService#unansweredUserMessage} возвращает именно его, второго вопроса не
     * заводится.
     */
    @Test
    void unansweredQuestionIsTheRowRetryReuses() {
        String conversationId = UUID.randomUUID().toString();
        long userMessageId = prePersistUser(conversationId, QUESTION);

        assertThat(memoryService().unansweredUserMessage(conversationId))
                .get()
                .extracting(ChatMessageEntity::getId, ChatMessageEntity::getContent)
                .containsExactly(userMessageId, QUESTION);
    }

    /** Ответ начался — повторять нечего: в хвосте уже стоит ASSISTANT. */
    @Test
    void startedAnswerClosesTheRetryWindow() {
        String conversationId = UUID.randomUUID().toString();
        ChatHistoryService memory = memoryService();

        prePersistUser(conversationId, QUESTION);
        memory.append(conversationId, List.of(new AssistantMessage("Начал отвеч")));

        assertThat(memory.unansweredUserMessage(conversationId)).isEmpty();
    }

    /**
     * Прогон упал во время вызова инструмента: в хвосте — {@code assistant.tool_calls} без ответа.
     * Это тоже начатый ответ, и достроенный ремонтом TOOL-ряд закрывает окно повтора. Порядок
     * важен: {@code repairDanglingToolCalls} обязана отработать ДО решения о повторе.
     */
    @Test
    void repairedToolCallTailClosesTheRetryWindow() {
        String conversationId = UUID.randomUUID().toString();
        ChatHistoryService memory = memoryService();

        prePersistUser(conversationId, QUESTION);
        memory.append(
                conversationId,
                List.of(
                        AssistantMessage.builder()
                                .content("смотрю файлы")
                                .toolCalls(
                                        List.of(
                                                new AssistantMessage.ToolCall(
                                                        "call-1", "function", "listFiles", "{}")))
                                .build()));

        memory.repairDanglingToolCalls(conversationId);

        assertThat(memory.unansweredUserMessage(conversationId)).isEmpty();
    }

    /** Пустой чат повторять нечего — ряда с вопросом просто нет. */
    @Test
    void emptyConversationHasNothingToRetry() {
        assertThat(memoryService().unansweredUserMessage(UUID.randomUUID().toString())).isEmpty();
    }

    /**
     * Контрольный случай, объясняющий, почему {@code .user(...)} убран из прогона: переданное в
     * промпт user-сообщение advisor памяти сохраняет как НОВЫЙ ряд — поверх предсохранённого.
     */
    @Test
    void passingUserOnTopOfPrePersistedRowCreatesSecondRow() {
        String conversationId = UUID.randomUUID().toString();
        ChatHistoryService memory = memoryService();
        ChatModel model = stubModel();

        prePersistUser(conversationId, QUESTION);

        chatClient(model, memory, false)
                .prompt()
                .system("Системный промпт")
                .user("Совсем другой вопрос")
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .call()
                .content();

        assertThat(userRows(conversationId))
                .extracting(ChatMessageEntity::getContent)
                .containsExactly(QUESTION, "Совсем другой вопрос");
    }
}
