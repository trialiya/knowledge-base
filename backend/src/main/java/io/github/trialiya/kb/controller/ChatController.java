package io.github.trialiya.kb.controller;

import static io.github.trialiya.kb.utils.ChatUtils.context;
import static io.github.trialiya.kb.utils.ChatUtils.getUser;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;

import io.github.trialiya.kb.config.ChatClientRegistry;
import io.github.trialiya.kb.config.model.ChatModeProperties;
import io.github.trialiya.kb.config.model.ChatModelProperties;
import io.github.trialiya.kb.model.chat.dto.Chat;
import io.github.trialiya.kb.model.chat.dto.ChatEventType;
import io.github.trialiya.kb.model.chat.dto.ChatMessage;
import io.github.trialiya.kb.model.chat.dto.ChatSearchResult;
import io.github.trialiya.kb.model.chat.dto.MessagePage;
import io.github.trialiya.kb.model.chat.dto.MessageSearchHit;
import io.github.trialiya.kb.model.chat.dto.StartRunRequest;
import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import io.github.trialiya.kb.model.chat.entity.ChatTopicEntity;
import io.github.trialiya.kb.model.chat.entity.ContextItem;
import io.github.trialiya.kb.model.project.Project;
import io.github.trialiya.kb.model.project.ProjectOptions;
import io.github.trialiya.kb.model.project.ProjectSwitch;
import io.github.trialiya.kb.model.tool.ToolCallDetail;
import io.github.trialiya.kb.repository.ChatTopicRepository;
import io.github.trialiya.kb.service.chat.context.ContextItemService;
import io.github.trialiya.kb.service.chat.memory.ChatHistoryService;
import io.github.trialiya.kb.service.chat.memory.ToolCallService;
import io.github.trialiya.kb.service.chat.prompt.ChatModeService;
import io.github.trialiya.kb.service.chat.prompt.ProjectPromptService;
import io.github.trialiya.kb.service.chat.prompt.SystemPromptService;
import io.github.trialiya.kb.service.chat.run.ChatEventService;
import io.github.trialiya.kb.service.chat.run.ChatRunService;
import io.github.trialiya.kb.service.chat.script.ScriptGuideService;
import io.github.trialiya.kb.service.chat.topic.ChatSearchService;
import io.github.trialiya.kb.service.chat.topic.ChatTopicService;
import io.github.trialiya.kb.service.file.git.GitRegistry;
import io.github.trialiya.kb.service.file.project.ProjectCatalog;
import io.github.trialiya.kb.tools.ToolInvocationCollector;
import jakarta.annotation.Nonnull;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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

@RestController
@RequestMapping("/api/chats")
@Slf4j
public class ChatController {

    private final ChatModelProperties chatModelProperties;
    private final ChatModeProperties chatModeProperties;
    private final ChatModeService chatModeService;
    private final ChatClientRegistry chatClients;
    private final ChatTopicRepository chatTopicRepository;
    private final ChatHistoryService chatHistory;
    private final ToolCallService toolCallService;
    private final ChatSearchService chatSearchService;
    private final ChatRunService chatRunService;
    private final ChatEventService chatEventService;
    private final ScriptGuideService scriptGuideService;
    private final ContextItemService contextItemService;
    private final ChatTopicService chatTopicService;
    private final ProjectCatalog projectCatalog;
    private final GitRegistry gitRegistry;
    private final SystemPromptService systemPromptService;
    private final ProjectPromptService projectPromptService;

    /** Часы аудита Spring Data — ими же датируется «тронуть чат», см. JdbcConfig#clock. */
    private final Clock clock;

    public ChatController(
            ChatModelProperties chatModelProperties,
            ChatModeProperties chatModeProperties,
            ChatModeService chatModeService,
            ChatClientRegistry chatClients,
            ChatTopicRepository chatTopicRepository,
            ChatHistoryService chatHistory,
            ToolCallService toolCallService,
            ChatSearchService chatSearchService,
            ChatRunService chatRunService,
            ChatEventService chatEventService,
            ScriptGuideService scriptGuideService,
            ContextItemService contextItemService,
            ChatTopicService chatTopicService,
            ProjectCatalog projectCatalog,
            GitRegistry gitRegistry,
            SystemPromptService systemPromptService,
            ProjectPromptService projectPromptService,
            Clock clock) {
        this.chatModelProperties = chatModelProperties;
        this.chatModeProperties = chatModeProperties;
        this.chatModeService = chatModeService;
        this.chatClients = chatClients;
        this.chatTopicRepository = chatTopicRepository;
        this.chatHistory = chatHistory;
        this.toolCallService = toolCallService;
        this.chatSearchService = chatSearchService;
        this.chatRunService = chatRunService;
        this.chatEventService = chatEventService;
        this.scriptGuideService = scriptGuideService;
        this.contextItemService = contextItemService;
        this.chatTopicService = chatTopicService;
        this.projectCatalog = projectCatalog;
        this.gitRegistry = gitRegistry;
        this.systemPromptService = systemPromptService;
        this.projectPromptService = projectPromptService;
        this.clock = clock;
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

    /** Готовые режимы ассистента (id/label). Дефолт — «без режима» (пустой выбор на фронте). */
    @GetMapping("/modes")
    public List<ChatModeProperties.ModeView> getModes() {
        return chatModeProperties.views();
    }

    /** Задать (или сбросить) режим чата. Пустое тело → «без режима». */
    @PutMapping("/{conversationId}/mode")
    public void updateChatMode(
            @PathVariable final String conversationId,
            @RequestBody(required = false) final String mode) {
        getChatTopic(conversationId); // 404/403 + проверка владельца
        final String trimmed = mode == null ? "" : mode.trim();
        if (!trimmed.isEmpty() && !chatModeProperties.isAllowed(trimmed)) {
            throw new ResponseStatusException(BAD_REQUEST, "Unknown mode: " + trimmed);
        }
        chatTopicRepository.updateMode(conversationId, trimmed.isEmpty() ? null : trimmed);
    }

    /** Lists the current user's chats (metadata only, no messages). */
    @GetMapping
    public List<Chat> getChats() {
        return chatTopicRepository.findAllByUserOrderByUpdatedAtDesc(getUser()).stream()
                .map(entity -> toChat(entity, null))
                .toList();
    }

    /**
     * Поиск чатов текущего пользователя по названию и/или содержимому сообщений (лупа над списком
     * чатов). Объединяет оба вида совпадений по чату.
     */
    @GetMapping("/search")
    public List<ChatSearchResult> searchChats(
            @RequestParam String q, @RequestParam(defaultValue = "20") int limit) {
        int safe = Math.min(Math.max(limit, 1), 50);
        return chatSearchService.searchChats(getUser(), q, safe);
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
        final @Nullable List<ChatMessage> messages =
                includeMessages
                        ? chatHistory.displayMessages(conversationId).stream()
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
        ChatHistoryService.Page page =
                (beforeCreatedAt != null && beforeId != null)
                        ? chatHistory.findPageBefore(
                                conversationId, beforeCreatedAt, beforeId, safe)
                        : chatHistory.findLatestPage(conversationId, safe);

        List<ChatMessage> dtos =
                page.messages().stream()
                        .map(
                                e ->
                                        new ChatMessage(
                                                e.getId(),
                                                e.getContent(),
                                                e.getType().name(),
                                                e.getCreatedAt(),
                                                // синтезирует меты из tool_data для сегментов
                                                // без meta.invocations (оборванные/старые прогоны)
                                                toolCallService.invocationsFor(e, page.messages()),
                                                e.getMeta() != null ? e.getMeta().runId() : null,
                                                isToolCalls(e),
                                                e.getContextItems(),
                                                e.getMeta() != null ? e.getMeta().project() : null,
                                                e.getMeta() != null
                                                        ? e.getMeta().projectSwitchFrom()
                                                        : null,
                                                e.getMeta() != null ? e.getMeta().model() : null))
                        .toList();
        return new MessagePage(dtos, page.hasMore(), page.oldestCursor());
    }

    /** Поиск сообщений внутри одного чата — для локального find-бара (Ctrl+F). */
    @GetMapping("/{conversationId}/messages/search")
    public List<MessageSearchHit> searchMessages(
            @PathVariable String conversationId, @RequestParam String q) {
        getChatTopic(conversationId); // 404/403 + проверка владельца
        return chatSearchService.searchMessages(conversationId, q);
    }

    @DeleteMapping("/{conversationId}")
    public void deleteChat(@PathVariable final String conversationId) {
        final ChatTopicEntity chatTopicEntity = getChatTopic(conversationId);
        // Если в чате идёт генерация — останавливаем, чтобы фоновый прогон не писал в удалённый
        // чат.
        chatRunService
                .activeRun(conversationId)
                .ifPresent(runId -> chatRunService.stop(conversationId, runId));
        chatTopicRepository.deleteById(chatTopicEntity.getConversationId());
        chatHistory.delete(conversationId);
        // Уведомляем открытые на этом чате вкладки (в т.ч. в других браузерах) — они закроют его.
        chatEventService.publishIfPresent(
                conversationId, ChatEventType.CHAT_DELETED, null, null, null);
    }

    /** Проекты, между которыми можно выбирать, и какой из них дефолтный. */
    @GetMapping("/projects")
    public ProjectOptions getProjects() {
        return gitRegistry.options();
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
                                            topic,
                                            chatTopicEntity.getAiTopic(),
                                            chatTopicEntity.getModel(),
                                            chatTopicEntity.getMode(),
                                            chatTopicEntity.getProject(),
                                            chatTopicEntity.getCreatedAt(),
                                            chatTopicEntity.getUpdatedAt(),
                                            false));
                        },
                        () ->
                                chatTopicRepository.save(
                                        new ChatTopicEntity(
                                                conversationId,
                                                getUser(),
                                                topic,
                                                null,
                                                null,
                                                null,
                                                null,
                                                // overwritten by @CreatedDate/@LastModifiedDate
                                                // auditing before insert
                                                LocalDateTime.now(),
                                                LocalDateTime.now(),
                                                true)));
    }

    // ---------------------------------------------------------------------
    //  Messages: /api/chats/{conversationId}/messages
    // ---------------------------------------------------------------------

    /**
     * Sends a user message and returns the assistant reply as a single JSON response. This is the
     * synchronous, non-streaming path; streaming goes through {@link #startRun} + {@link #events}.
     */
    @PostMapping(value = "/{conversationId}/messages", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<String> createMessage(
            @PathVariable final String conversationId,
            @RequestParam(name = "model", required = false) final String model,
            @RequestParam(name = "mode", required = false) final String mode,
            @RequestParam(name = "project", required = false) final String project,
            @RequestBody final String userMessage) {
        checkChat(conversationId, true);
        final ChatRunService.RunOptions options = resolveRun(conversationId, model, mode, project);
        final String resolvedModel = options.model();
        final ToolInvocationCollector toolCollector = new ToolInvocationCollector();

        ChatClient.ChatClientRequestSpec spec =
                chatClients
                        .forModel(resolvedModel)
                        .prompt()
                        .system(
                                sp ->
                                        sp.param("mode_instructions", options.modeInstructions())
                                                .param(
                                                        "script_instructions",
                                                        scriptGuideService.instructions(
                                                                options.weakModel(),
                                                                options.project()))
                                                .param(
                                                        "system_extended",
                                                        systemPromptService.systemExtended(
                                                                options.weakModel()))
                                                .param(
                                                        "project_context",
                                                        projectPromptService.context(
                                                                options.project())))
                        .user(userMessage)
                        .toolContext(
                                context(conversationId)
                                        .project(options.project())
                                        .collector(toolCollector)
                                        .build())
                        .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId));
        if (resolvedModel != null) {
            spec = spec.options(OpenAiChatOptions.builder().model(resolvedModel));
        }

        final ChatResponse chatResponse = spec.call().chatResponse();

        return Optional.ofNullable(chatResponse).map(ChatResponse::getResults).stream()
                .flatMap(Collection::stream)
                .map(generation -> generation.getOutput().getText())
                .toList();
    }

    // ---------------------------------------------------------------------
    //  Background runs + event channel (streaming, multi-tab, resume, stop)
    // ---------------------------------------------------------------------

    /**
     * Запускает генерацию ответа как фоновую задачу и сразу возвращает {@code runId} и {@code
     * messageId} сохранённого вопроса. Сам ответ приходит не в этом запросе, а потоком событий
     * через {@link #events}. Это и есть развязка «обработка ≠ HTTP-запрос»: ответ переживает
     * перезагрузку страницы и виден всем вкладкам.
     *
     * <p>Вопрос пользователя сохраняется синхронно, до старта генерации (см. {@link
     * ChatHistoryService#saveUserMessage}), поэтому ошибка записи — это ошибка этого запроса, а не
     * тихо потерянное сообщение.
     *
     * <p>Тело — {@link StartRunRequest}: текст вопроса и приложенный к нему контекст (вложения).
     * Ссылки на контекст проверяются здесь же и уходят в {@code meta} того же ряда, поэтому
     * привязка не требует ни знания id заранее, ни второго запроса.
     *
     * @param retry повтор упавшего прогона: тело не нужно, новое сообщение не появляется — ходом
     *     остаётся последний неотвеченный вопрос. Если модель уже начала отвечать, повторять
     *     нечего: 422, дальше пользователь пишет сам (см. {@link
     *     ChatHistoryService#unansweredUserMessage})
     * @param clientMsgId идентификатор клиента — чтобы вкладка-отправитель не задвоила свой
     *     оптимистично показанный пузырь, получив его же эхом
     */
    @PostMapping("/{conversationId}/runs")
    public Map<String, Object> startRun(
            @PathVariable final String conversationId,
            @RequestParam(name = "model", required = false) final String model,
            @RequestParam(name = "mode", required = false) final String mode,
            @RequestParam(name = "project", required = false) final String project,
            @RequestParam(name = "clientMsgId", required = false) final String clientMsgId,
            @RequestParam(name = "retry", defaultValue = "false") final boolean retry,
            @RequestBody(required = false) final StartRunRequest body) {
        final String userMessage = body == null ? null : body.text();
        if (!retry && !StringUtils.hasText(userMessage)) {
            throw new ResponseStatusException(BAD_REQUEST, "Empty message");
        }
        checkChat(conversationId, true);
        // Проверяем приложенное ДО заявки на чат: 404 на чужое вложение не должен оставлять
        // за собой ни занятый чат, ни записанный вопрос.
        final List<ContextItem> contextItems =
                retry
                        ? List.of()
                        : contextItemService.resolve(
                                conversationId, body == null ? null : body.contextItems());
        final ChatRunService.StartedRun started =
                chatRunService.start(
                        conversationId,
                        getUser(),
                        retry ? null : userMessage,
                        contextItems,
                        resolveRun(conversationId, model, mode, project),
                        clientMsgId);
        return Map.of("runId", started.runId(), "messageId", started.userMessageId());
    }

    /**
     * Поток Server-Sent Events для чата: и стриминг текущего ответа, и кросс-вкладочная
     * синхронизация. При подключении сначала реплеятся пропущенные события (seq &gt; {@code
     * fromSeq}), затем идут живые — так вкладка догоняет генерацию после перезагрузки/позднего
     * открытия.
     */
    @GetMapping(value = "/{conversationId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(
            @PathVariable final String conversationId,
            @RequestParam(name = "fromSeq", defaultValue = "0") final long fromSeq) {
        // Намеренно не 404-им на отсутствующий чат: вкладка может подписаться чуть раньше, чем
        // первый run создаст запись в БД. Если чат есть — проверяем владельца.
        verifyOwnerIfPresent(conversationId);
        return chatEventService.subscribe(conversationId, fromSeq);
    }

    /**
     * Полные детали одного вызова инструмента — точечно по протокольному {@code callId} (см. {@link
     * ToolCallService#findToolCallDetail}); messageId/responseMessageId бэк находит сам через
     * {@code tool_call_index}.
     */
    @GetMapping("/{conversationId}/tool-calls")
    public ResponseEntity<ToolCallDetail> getToolCallDetails(
            @PathVariable String conversationId, @RequestParam String callId) {
        verifyOwnerIfPresent(conversationId);
        return toolCallService
                .findToolCallDetail(conversationId, callId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /** Останавливает активный прогон. Идемпотентно: на неизвестный runId — просто no-op. */
    @PostMapping("/{conversationId}/runs/{runId}/stop")
    public void stopRun(
            @PathVariable final String conversationId, @PathVariable final String runId) {
        verifyOwnerIfPresent(conversationId);
        chatRunService.stop(conversationId, runId);
    }

    /**
     * runId активного прогона чата (или пустой объект) — для восстановления состояния на фронте.
     */
    @GetMapping("/{conversationId}/runs/active")
    public Map<String, String> activeRun(@PathVariable final String conversationId) {
        verifyOwnerIfPresent(conversationId);
        return chatRunService
                .activeRun(conversationId)
                .map(runId -> Map.of("runId", runId))
                .orElseGet(Map::of);
    }

    // ---------------------------------------------------------------------
    //  Helpers
    // ---------------------------------------------------------------------

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

    /**
     * Проверяет владельца, только если чат уже существует (для подписки/стопа без жёсткого 404).
     */
    private void verifyOwnerIfPresent(String conversationId) {
        chatTopicRepository
                .findById(conversationId)
                .ifPresent(
                        chatTopicEntity -> {
                            if (!chatTopicEntity.getUser().equals(getUser())) {
                                throw new ResponseStatusException(FORBIDDEN, "Forbidden");
                            }
                        });
    }

    private void checkChat(@Nonnull final String conversationId, boolean update) {
        // Заведение чата — общее с загрузкой вложений (см. ChatTopicService): вложение тоже
        // может оказаться первым, что делают в ещё не начатом разговоре.
        if (chatTopicService.ensureExists(conversationId) && update) {
            chatTopicRepository.updateUpdatedAt(conversationId, LocalDateTime.now(clock));
        }
    }

    private Chat toChat(ChatTopicEntity entity, @Nullable List<ChatMessage> messages) {
        return new Chat(
                entity.getConversationId(),
                entity.getUser(),
                entity.getDisplayTopic(),
                entity.getAiTopic(),
                entity.getModel(),
                entity.getMode(),
                entity.getProject(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                messages);
    }

    private ChatMessage toChatMessage(ChatMessageEntity chatMessageEntity) {
        final String message;
        // «Крошки» вызовов инструментов хранят PREAMBLE + JSON: показываем только преамбулу.
        // Раньше их отличали по типу SYSTEM, потом по наличию meta — теперь по явному флагу
        // meta.toolCalls, чтобы не путать с другими сообщениями, у которых может появиться meta.
        if (isToolCalls(chatMessageEntity) && chatMessageEntity.getText() != null) {
            final int i = chatMessageEntity.getText().indexOf("\n{");
            message =
                    i > 0
                            ? chatMessageEntity.getText().substring(0, i)
                            : chatMessageEntity.getText();
        } else {
            message = chatMessageEntity.getText();
        }
        final var meta = chatMessageEntity.getMeta();
        return new ChatMessage(
                chatMessageEntity.getId(),
                message,
                chatMessageEntity.getMessageType().getValue(),
                chatMessageEntity.getCreatedAt(),
                chatMessageEntity.getInvocations(),
                meta != null ? meta.runId() : null,
                isToolCalls(chatMessageEntity),
                chatMessageEntity.getContextItems(),
                meta != null ? meta.project() : null,
                meta != null ? meta.projectSwitchFrom() : null,
                meta != null ? meta.model() : null);
    }

    /** «Крошка» вызовов инструментов — служебное сообщение, которое не показываем пользователю. */
    private static boolean isToolCalls(ChatMessageEntity entity) {
        return entity.getMeta() != null && entity.getMeta().toolCalls();
    }

    /**
     * Параметр запроса → сохранённая модель чата → null. {@code null} означает «не переопределять»,
     * т.е. едем на модели из application.yaml.
     *
     * @param stored строка чата, если её уже прочитали; пустая — параметр запроса всё решает сам
     */
    private @Nullable String resolveModel(
            final String conversationId,
            final Optional<ChatTopicEntity> stored,
            final String requested) {
        if (StringUtils.hasText(requested)) {
            if (!chatModelProperties.isAllowed(requested)) {
                throw new ResponseStatusException(BAD_REQUEST, "Unknown model: " + requested);
            }
            chatTopicRepository.updateModel(
                    conversationId, requested); // запоминаем как «последнюю»
            return requested;
        }
        return stored.map(ChatTopicEntity::getModel)
                .filter(StringUtils::hasText)
                .filter(chatModelProperties::isAllowed) // на случай, если модель убрали из конфига
                .orElse(null);
    }

    /**
     * Параметр запроса → сохранённый режим чата → null. {@code null} означает «без режима»
     * (плейсхолдер {@code mode_instructions} заполняется пустой строкой). Параллель {@link
     * #resolveModel}.
     */
    private @Nullable String resolveMode(
            final String conversationId,
            final Optional<ChatTopicEntity> stored,
            final String requested) {
        if (StringUtils.hasText(requested)) {
            if (!chatModeProperties.isAllowed(requested)) {
                throw new ResponseStatusException(BAD_REQUEST, "Unknown mode: " + requested);
            }
            chatTopicRepository.updateMode(conversationId, requested); // запоминаем как «последний»
            return requested;
        }
        return stored.map(ChatTopicEntity::getMode)
                .filter(StringUtils::hasText)
                .filter(chatModeProperties::isAllowed) // на случай, если режим убрали из конфига
                .orElse(null);
    }

    /**
     * Параметр запроса → сохранённый проект чата → null. {@code null} означает «проект не назван»:
     * инструменты прогона поедут на первом проекте списка (см. {@code ProjectCatalog}). Параллель
     * {@link #resolveModel}.
     *
     * <p>В отличие от модели и режима, колонку пишет не этот метод, а {@link #resolveRun} — одной
     * записью «привести к тому, на чём прогон реально пошёл». Ответ здесь бывает не тем, что
     * сохранено (выбывший из конфигурации проект вырождается в дефолтный), и записать надо именно
     * ответ: {@code chat_topic.project} означает «на каком проекте чат реально работал», а не «что
     * выбрано в селекторе». Выбор, не подтверждённый отправкой, живёт на фронте; поэтому же
     * сравнение с прежним значением колонки (см. {@link #projectSwitch}) и есть детекция настоящей
     * смены проекта.
     */
    private @Nullable String resolveProject(
            final Optional<ChatTopicEntity> stored, final String requested) {
        if (StringUtils.hasText(requested)) {
            if (!projectCatalog.isAllowed(requested)) {
                throw new ResponseStatusException(BAD_REQUEST, "Unknown project: " + requested);
            }
            return requested;
        }
        return stored.map(ChatTopicEntity::getProject)
                .filter(StringUtils::hasText)
                .filter(projectCatalog::isAllowed) // на случай, если проект убрали из конфига
                .orElse(null);
    }

    /**
     * Смена проекта относительно того, на котором чат работал до этого сообщения. Сравнение — по
     * каноническим id: не названный проект и явно названный дефолтный означают один репозиторий.
     * Проект, выбывший из конфигурации, канонизировать не во что — его id сравнивается как есть, и
     * переезд с него на дефолтный тоже смена: история-то читана в другом репозитории.
     */
    private @Nullable ProjectSwitch projectSwitch(
            @Nullable final String previous, @Nullable final String resolved) {
        final String to = projectCatalog.require(resolved).id();
        final String from =
                previous == null
                        ? projectCatalog.defaultProject().id()
                        : projectCatalog.find(previous).map(Project::id).orElse(previous);
        return to.equals(from) ? null : new ProjectSwitch(from, to);
    }

    /**
     * Настройки прогона из параметров запроса и памяти чата — один вызов на оба пути генерации
     * (синхронный {@link #createMessage} и фоновый {@link #startRun}), чтобы «что выбрано в этом
     * чате» решалось для них одинаково.
     */
    private ChatRunService.RunOptions resolveRun(
            final String conversationId,
            final String model,
            final String mode,
            final String project) {
        // Одна строка chat_topic на все три разрешения (тремя отдельными чтениями это был бы тот
        // же SELECT трижды на сообщение). Проекту она нужна даже при пришедшем параметре: прежнее
        // значение колонки — это «на каком проекте шла история», и сравнение с ним даёт маркер
        // смены проекта.
        final Optional<ChatTopicEntity> stored = chatTopicRepository.findById(conversationId);
        final String resolvedModel = resolveModel(conversationId, stored, model);
        final String previousProject = stored.map(ChatTopicEntity::getProject).orElse(null);
        final String resolvedProject = resolveProject(stored, project);
        final ProjectSwitch switched = projectSwitch(previousProject, resolvedProject);
        if (!Objects.equals(previousProject, resolvedProject)) {
            // Колонку приводим к тому, на чём прогон реально пошёл, — и когда проект назвали, и
            // когда сохранённый выбыл из конфигурации и выродился в дефолтный. Второе не записать
            // нельзя: следующее сообщение сравнилось бы с тем же выбывшим значением и повторило
            // маркер, которому место ровно на одном вопросе — том, которым история сменила
            // репозиторий.
            chatTopicRepository.updateProject(conversationId, resolvedProject);
        }
        return new ChatRunService.RunOptions(
                resolvedModel,
                chatModelProperties.isWeak(resolvedModel),
                chatModeService.instructionsFor(resolveMode(conversationId, stored, mode)),
                resolvedProject,
                switched);
    }
}
