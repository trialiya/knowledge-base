package io.github.trialiya.kb.controller;

import static io.github.trialiya.kb.utils.ChatUtils.buildContext;
import static io.github.trialiya.kb.utils.ChatUtils.getUser;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;

import io.github.trialiya.kb.config.model.ChatModelProperties;
import io.github.trialiya.kb.model.chat.dto.Chat;
import io.github.trialiya.kb.model.chat.dto.ChatMessage;
import io.github.trialiya.kb.model.chat.dto.CreateJiraChatRequest;
import io.github.trialiya.kb.model.chat.dto.MessagePage;
import io.github.trialiya.kb.model.chat.dto.StreamMessage;
import io.github.trialiya.kb.model.chat.dto.ToolCallMessage;
import io.github.trialiya.kb.model.chat.dto.ToolCallsMessage;
import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import io.github.trialiya.kb.model.chat.entity.ChatTopicEntity;
import io.github.trialiya.kb.model.tool.ToolInvocation;
import io.github.trialiya.kb.repository.ChatTopicRepository;
import io.github.trialiya.kb.service.ChatMemoryService;
import io.github.trialiya.kb.service.JiraChatService;
import io.github.trialiya.kb.service.SummarizeService;
import io.github.trialiya.kb.tools.ToolInvocationCollector;
import jakarta.annotation.Nonnull;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AbstractMessage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.publisher.SignalType;

@RestController
@RequestMapping("/api/chats")
@Slf4j
public class ChatController {

    private final ChatModelProperties chatModelProperties;
    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    private final ChatTopicRepository chatTopicRepository;
    private final ChatMemoryService chatMemoryService;
    private final SummarizeService summarizeService;
    private final JiraChatService jiraChatService;

    public ChatController(
            ChatModelProperties chatModelProperties,
            ChatClient chatClient,
            ChatMemory chatMemory,
            ChatTopicRepository chatTopicRepository,
            ChatMemoryService chatMemoryService,
            SummarizeService summarizeService,
            JiraChatService jiraChatService) {
        this.chatModelProperties = chatModelProperties;
        this.chatClient = chatClient;
        this.chatMemory = chatMemory;
        this.chatTopicRepository = chatTopicRepository;
        this.chatMemoryService = chatMemoryService;
        this.summarizeService = summarizeService;
        this.jiraChatService = jiraChatService;
    }

    /** Список выбираемых моделей и какая из них дефолтная. */
    @GetMapping("/models")
    public ChatModelProperties getModels() {
        return chatModelProperties;
    }

    /** Задать (или сбросить) модель чата. Пустое тело → возврат к дефолтной. */
    @PutMapping("/{conversationId}/model")
    public void updateChatModel(
            @PathVariable final String conversationId,
            @RequestBody(required = false) final String model) {
        getChatTopic(conversationId); // 404/403 + проверка владельца
        final String trimmed = model == null ? "" : model.trim();
        if (!trimmed.isEmpty() && !chatModelProperties.isAllowed(trimmed)) {
            throw new ResponseStatusException(BAD_REQUEST, "Unknown model: " + trimmed);
        }
        chatTopicRepository.updateModel(conversationId, trimmed.isEmpty() ? null : trimmed);
    }

    /** Lists the current user's chats (metadata only, no messages). */
    @GetMapping
    public List<Chat> getChats() {
        return chatTopicRepository.findAllByUserOrderByUpdatedAtDesc(getUser()).stream()
                .map(entity -> toChat(entity, null))
                .toList();
    }

    // ---------------------------------------------------------------------
    //  Single chat: /api/chats/{conversationId}
    // ---------------------------------------------------------------------

    /**
     * Returns a single chat. Messages are included by default; pass {@code includeMessages=false}
     * for the lightweight metadata-only projection (the former {@code /chat/short}).
     */
    @GetMapping("/{conversationId}")
    public Chat getChat(
            @PathVariable final String conversationId,
            @RequestParam(name = "includeMessages", defaultValue = "true")
                    final boolean includeMessages) {
        final ChatTopicEntity chatTopicEntity = getChatTopic(conversationId);
        final List<ChatMessage> messages =
                includeMessages
                        ? Optional.ofNullable(
                                        chatMemoryService.findChatMessageByConversationId(
                                                conversationId))
                                .stream()
                                .flatMap(Collection::stream)
                                .filter(a -> a.getText() != null && !a.getText().isBlank())
                                .map(this::toChatMessage)
                                .toList()
                        : null;
        return toChat(chatTopicEntity, messages);
    }

    @GetMapping("/{conversationId}/messages")
    public MessagePage getMessages(
            @PathVariable String conversationId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    LocalDateTime beforeCreatedAt,
            @RequestParam(required = false) Long beforeId,
            @RequestParam(defaultValue = "20") int limit) {

        int safe = Math.min(Math.max(limit, 1), 100);
        ChatMemoryService.Page page =
                (beforeCreatedAt != null && beforeId != null)
                        ? chatMemoryService.findPageBefore(
                                conversationId, beforeCreatedAt, beforeId, safe)
                        : chatMemoryService.findLatestPage(conversationId, safe);

        List<ChatMessage> dtos =
                page.messages().stream()
                        .map(
                                e ->
                                        new ChatMessage(
                                                e.getContent(),
                                                e.getType().name(),
                                                e.getCreatedAt(),
                                                e.getInvocations()))
                        .toList();
        return new MessagePage(dtos, page.hasMore(), page.oldestCursor());
    }

    @DeleteMapping("/{conversationId}")
    public void deleteChat(@PathVariable final String conversationId) {
        final ChatTopicEntity chatTopicEntity = getChatTopic(conversationId);
        chatTopicRepository.deleteById(chatTopicEntity.getConversationId());
        chatMemory.clear(conversationId);
    }

    /** Sets (or creates) the chat's topic. Idempotent, hence PUT. */
    @PutMapping("/{conversationId}/topic")
    public void updateChatTopic(
            @PathVariable final String conversationId, @RequestBody final String topic) {
        chatTopicRepository
                .findById(conversationId)
                .ifPresentOrElse(
                        chatTopicEntity -> {
                            if (!chatTopicEntity.getUser().equals(getUser())) {
                                throw new ResponseStatusException(FORBIDDEN, "Forbidden");
                            }
                            chatTopicRepository.save(
                                    new ChatTopicEntity(
                                            chatTopicEntity.getConversationId(),
                                            chatTopicEntity.getUser(),
                                            true,
                                            topic,
                                            chatTopicEntity.getModel(),
                                            chatTopicEntity.getCreatedAt(),
                                            chatTopicEntity.getUpdatedAt(),
                                            false));
                        },
                        () ->
                                chatTopicRepository.save(
                                        new ChatTopicEntity(
                                                conversationId,
                                                getUser(),
                                                true,
                                                topic,
                                                null,
                                                null,
                                                null,
                                                true)));
    }

    // ---------------------------------------------------------------------
    //  Messages: /api/chats/{conversationId}/messages
    // ---------------------------------------------------------------------

    /**
     * Sends a user message and returns the assistant reply as a single JSON response.
     *
     * <p>Shares its URI with {@link #streamMessage}; the two are selected by content negotiation
     * (this one is chosen for {@code Accept: application/json}).
     */
    @PostMapping(value = "/{conversationId}/messages", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<String> createMessage(
            @PathVariable final String conversationId,
            @RequestParam(name = "model", required = false) final String model,
            @RequestBody final String userMessage) {
        checkChat(conversationId, true);
        final String resolvedModel = resolveModel(conversationId, model);
        final ToolInvocationCollector toolCollector = new ToolInvocationCollector();

        ChatClient.ChatClientRequestSpec spec =
                chatClient
                        .prompt()
                        .user(userMessage)
                        .toolContext(buildContext(conversationId, toolCollector))
                        .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId));
        if (resolvedModel != null) {
            spec = spec.options(OpenAiChatOptions.builder().model(resolvedModel).build());
        }

        final ChatResponse chatResponse = spec.call().chatResponse();

        return Optional.ofNullable(chatResponse).map(ChatResponse::getResults).stream()
                .flatMap(Collection::stream)
                .map(generation -> generation.getOutput().getText())
                .toList();
    }

    /**
     * Sends a user message and streams the assistant reply as Server-Sent Events.
     *
     * <p>Shares its URI with {@link #createMessage}; this one is chosen for {@code Accept:
     * text/event-stream}.
     */
    @PostMapping(
            value = "/{conversationId}/messages/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamMessage(
            @PathVariable String conversationId,
            @RequestParam(name = "model", required = false) String model,
            @RequestBody String userMessage) {
        checkChat(conversationId, true);
        final String resolvedModel = resolveModel(conversationId, model);

        final SseEmitter emitter = new SseEmitter(Duration.ofMinutes(30).toMillis());
        final AtomicReference<Disposable> disposableRef = new AtomicReference<>();
        final Consumer<Object> liveSink = sendSseEmitterData(emitter);
        final ToolInvocationCollector toolCollector = new ToolInvocationCollector(liveSink);
        final StringBuffer buffer = new StringBuffer();
        final AtomicBoolean persisted = new AtomicBoolean(false);

        try {
            ChatClient.ChatClientRequestSpec spec =
                    chatClient
                            .prompt()
                            .user(userMessage)
                            .toolContext(buildContext(conversationId, toolCollector))
                            .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId));
            if (resolvedModel != null) {
                spec = spec.options(OpenAiChatOptions.builder().model(resolvedModel).build());
            }

            Disposable disposable =
                    spec.stream()
                            .chatResponse()
                            .doFinally(
                                    signal -> {
                                        // onComplete сохранит advisor — пропускаем.
                                        // прерывание/ошибка — спасаем накопленное сами
                                        if (signal == SignalType.CANCEL
                                                || signal == SignalType.ON_ERROR) {
                                            persistPartial(
                                                    conversationId,
                                                    buffer,
                                                    toolCollector,
                                                    persisted);
                                        }
                                    })
                            .subscribe(
                                    response -> {
                                        final String chunk =
                                                Optional.ofNullable(response)
                                                        .map(ChatResponse::getResult)
                                                        .map(Generation::getOutput)
                                                        .map(AbstractMessage::getText)
                                                        .orElse("");
                                        final String finishReason =
                                                Optional.ofNullable(response)
                                                        .map(ChatResponse::getResult)
                                                        .map(Generation::getMetadata)
                                                        .map(
                                                                ChatGenerationMetadata
                                                                        ::getFinishReason)
                                                        .orElse(null);
                                        if (finishReason != null) {
                                            buffer.setLength(0);
                                        } else {
                                            buffer.append(chunk);
                                        }
                                        liveSink.accept(new StreamMessage(chunk, finishReason));
                                        printUsageStatistics(
                                                conversationId, response, finishReason);
                                    },
                                    emitter::completeWithError,
                                    () -> {
                                        persisted.set(true);
                                        liveSink.accept(
                                                new ToolCallsMessage(
                                                        toolCollector.completedSnapshot().stream()
                                                                .map(ToolInvocation::toMeta)
                                                                .toList()));
                                        chatMemoryService.saveToolCalls(
                                                conversationId, toolCollector.completedSnapshot());
                                        liveSink.accept(new StreamMessage("", "DONE"));
                                        summarizeService.trySummarize(conversationId);
                                        emitter.complete();
                                    });

            disposableRef.set(disposable);
            emitter.onTimeout(
                    () -> {
                        dispose(disposableRef);
                        emitter.complete();
                    });
            emitter.onError(
                    ex -> {
                        log.error("SSE error {}", conversationId, ex);
                        dispose(disposableRef);
                    });
            emitter.onCompletion(
                    // важно: разрыв клиентом → CANCEL → частичное сохранение
                    () -> dispose(disposableRef));
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
        return emitter;
    }

    /**
     * Debug-only endpoint that streams a canned sequence of messages and tool calls. Not part of
     * the public API contract — consider guarding it behind a Spring profile or removing it before
     * release.
     */
    @PostMapping(
            value = "/{conversationId}/messages/test",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamTestMessage(
            @PathVariable final String conversationId, @RequestBody final String userMessage) {
        checkChat(conversationId, true);

        final SseEmitter emitter = new SseEmitter(300_000L);

        Executors.newVirtualThreadPerTaskExecutor()
                .submit(
                        () -> {
                            final Consumer<Object> liveSink = sendSseEmitterData(emitter);
                            liveSink.accept(new StreamMessage("Ok", null));
                            testToolCalls(liveSink, "test", Map.of());
                            testToolCalls(liveSink, "files", Map.of("id", 1));
                            for (int j = 1; j < 7; j++) {
                                for (int index = 0; index < j; index++) {
                                    liveSink.accept(
                                            new StreamMessage(
                                                    "reading file " + index + "\n", null));
                                    testToolCalls(
                                            liveSink,
                                            "file" + j % 2,
                                            Map.of(
                                                    "id",
                                                    index + j * 10,
                                                    "action",
                                                    "readingreadingreadingreading reading file id = "
                                                            + index));
                                }
                                if (Math.random() < 0.2) {
                                    emitter.completeWithError(new RuntimeException());
                                    return;
                                }
                                if (j % 2 == 0) {
                                    liveSink.accept(new StreamMessage("\n\n", null));
                                }
                            }
                            emitter.complete();
                        });
        return emitter;
    }

    // ---------------------------------------------------------------------
    //  JIRA-backed chats
    // ---------------------------------------------------------------------

    /**
     * Creates a new "JIRA chat" — a conversation pre-loaded with context from a JIRA issue and
     * optionally a Confluence page.
     *
     * <pre>
     * POST /api/chats/jira
     * {
     *   "jiraUrl": "https://instance.atlassian.net/browse/PROJ-123",
     *   "confluenceUrl": "https://instance.atlassian.net/wiki/spaces/.../pages/12345",
     *   "title": "Optional custom title"
     * }
     * </pre>
     *
     * @return the created Chat (with empty messages list)
     */
    @PostMapping("/jira")
    public Chat createJiraChat(@RequestBody CreateJiraChatRequest request) {
        return jiraChatService.createJiraChat(request);
    }

    /**
     * Refreshes a JIRA chat by re-fetching issue data and replacing attachments.
     *
     * <pre>
     * POST /api/chats/{conversationId}/refresh
     * Body: "https://instance.atlassian.net/browse/PROJ-123"   (raw JIRA URL string)
     * </pre>
     *
     * @return updated Chat metadata
     */
    @PostMapping("/{conversationId}/refresh")
    public Chat refreshJiraChat(@PathVariable String conversationId, @RequestBody String jiraUrl) {
        return jiraChatService.refreshJiraChat(conversationId, jiraUrl.trim());
    }

    // ---------------------------------------------------------------------
    //  Helpers
    // ---------------------------------------------------------------------

    private static void testToolCalls(
            Consumer<Object> liveSink, String name, Map<Object, Object> arguments) {
        liveSink.accept(
                new ToolCallMessage(
                        new ToolInvocation(
                                name,
                                arguments,
                                ToolInvocationCollector.ToolInvocationStatus.STARTED,
                                null,
                                null,
                                null)));
        try {
            TimeUnit.MILLISECONDS.sleep(400);
        } catch (InterruptedException e) {
            // nothing - testing
        }
        liveSink.accept(
                new ToolCallMessage(
                        new ToolInvocation(
                                name,
                                arguments,
                                ToolInvocationCollector.ToolInvocationStatus.OK,
                                null,
                                null,
                                "ok")));
    }

    private static void dispose(AtomicReference<Disposable> ref) {
        Disposable d = ref.get();
        if (d != null && !d.isDisposed()) {
            d.dispose();
        }
    }

    private void persistPartial(
            String conversationId,
            StringBuffer buffer,
            ToolInvocationCollector toolCollector,
            AtomicBoolean persisted) {
        if (!persisted.compareAndSet(false, true)) {
            // уже сохранили (onError + doFinally могут прийти оба)
            return;
        }
        final String text = buffer.toString();
        if (text.isBlank()) {
            return;
        }
        try {
            chatMemory.add(conversationId, new AssistantMessage(text));
            chatMemoryService.saveToolCalls(conversationId, toolCollector.completedSnapshot());
            log.info(
                    "Saved partial assistant reply for {} ({} chars)",
                    conversationId,
                    text.length());
        } catch (Exception e) {
            log.warn("Failed to persist partial reply for {}", conversationId, e);
        }
    }

    private @NonNull ChatTopicEntity getChatTopic(String conversationId) {
        final ChatTopicEntity chatTopicEntity =
                chatTopicRepository
                        .findById(conversationId)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                NOT_FOUND,
                                                "Not found conversation id " + conversationId));
        if (!chatTopicEntity.getUser().equals(getUser())) {
            throw new ResponseStatusException(FORBIDDEN, "Forbidden");
        }
        return chatTopicEntity;
    }

    private void checkChat(@Nonnull final String conversationId, boolean update) {
        chatTopicRepository
                .findById(conversationId)
                .ifPresentOrElse(
                        chatTopicEntity -> {
                            if (!chatTopicEntity.getUser().equals(getUser())) {
                                throw new ResponseStatusException(FORBIDDEN, "Forbidden");
                            }
                            if (update) {
                                chatTopicRepository.updateUpdatedAt(conversationId);
                            }
                        },
                        () ->
                                chatTopicRepository.save(
                                        new ChatTopicEntity(
                                                conversationId,
                                                getUser(),
                                                false,
                                                "",
                                                null,
                                                null,
                                                null,
                                                true)));
    }

    private static Consumer<Object> sendSseEmitterData(@Nonnull final SseEmitter emitter) {
        final Lock lock = new ReentrantLock();
        return obj -> {
            lock.lock();
            try {
                emitter.send(obj, MediaType.APPLICATION_JSON);
            } catch (Exception e) {
                log.warn("Failed to push tool call to SSE: {}", e.getMessage());
            } finally {
                lock.unlock();
            }
        };
    }

    private void printUsageStatistics(
            String conversationId, ChatResponse response, String finishReason) {
        if (finishReason == null || finishReason.isEmpty()) {
            return;
        }
        Optional.ofNullable(response)
                .map(ChatResponse::getMetadata)
                .map(ChatResponseMetadata::getUsage)
                .ifPresent(
                        usage -> {
                            log.info("[{}] FinishReason: {}", conversationId, finishReason);
                            log.info(
                                    "[{}] Usage:\n PromptToken: {}\n CompletionTokens: {}\n TotalTokens: {}",
                                    conversationId,
                                    usage.getPromptTokens(),
                                    usage.getCompletionTokens(),
                                    usage.getTotalTokens());
                        });
    }

    private Chat toChat(ChatTopicEntity entity, List<ChatMessage> messages) {
        return new Chat(
                entity.getConversationId(),
                entity.getUser(),
                entity.getTopic(),
                entity.getModel(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                messages);
    }

    private ChatMessage toChatMessage(ChatMessageEntity chatMessageEntity) {
        final String message;
        if (chatMessageEntity.getMessageType() == MessageType.SYSTEM
                && chatMessageEntity.getText() != null) {
            final int i = chatMessageEntity.getText().indexOf("\n{");
            message =
                    i > 0
                            ? chatMessageEntity.getText().substring(0, i)
                            : chatMessageEntity.getText();
        } else {
            message = chatMessageEntity.getText();
        }
        return new ChatMessage(
                message,
                chatMessageEntity.getMessageType().getValue(),
                chatMessageEntity.getCreatedAt(),
                chatMessageEntity.getInvocations());
    }

    /**
     * Параметр запроса → сохранённая модель чата → null. {@code null} означает «не переопределять»,
     * т.е. едем на модели из application.yaml.
     */
    private String resolveModel(final String conversationId, final String requested) {
        if (StringUtils.hasText(requested)) {
            if (!chatModelProperties.isAllowed(requested)) {
                throw new ResponseStatusException(BAD_REQUEST, "Unknown model: " + requested);
            }
            chatTopicRepository.updateModel(
                    conversationId, requested); // запоминаем как «последнюю»
            return requested;
        }
        return chatTopicRepository
                .findById(conversationId)
                .map(ChatTopicEntity::getModel)
                .filter(StringUtils::hasText)
                .filter(chatModelProperties::isAllowed) // на случай, если модель убрали из конфига
                .orElse(null);
    }
}
