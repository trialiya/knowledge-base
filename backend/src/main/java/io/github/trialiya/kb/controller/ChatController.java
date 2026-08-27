package io.github.trialiya.kb.controller;

import static io.github.trialiya.kb.utils.ChatUtils.context;
import static io.github.trialiya.kb.utils.ChatUtils.getUser;
import static org.springframework.http.HttpStatus.ACCEPTED;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;

import io.github.trialiya.kb.config.ChatClientRegistry;
import io.github.trialiya.kb.config.model.ChatModeProperties;
import io.github.trialiya.kb.config.model.ChatModelProperties;
import io.github.trialiya.kb.model.chat.dto.Chat;
import io.github.trialiya.kb.model.chat.dto.ChatEventType;
import io.github.trialiya.kb.model.chat.dto.ChatMessage;
import io.github.trialiya.kb.model.chat.dto.ChatSearchResult;
import io.github.trialiya.kb.model.chat.dto.CompactDetail;
import io.github.trialiya.kb.model.chat.dto.CompactRequest;
import io.github.trialiya.kb.model.chat.dto.MessagePage;
import io.github.trialiya.kb.model.chat.dto.MessageSearchHit;
import io.github.trialiya.kb.model.chat.dto.StartRunRequest;
import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import io.github.trialiya.kb.model.chat.entity.ChatTopicEntity;
import io.github.trialiya.kb.model.chat.entity.ContextItem;
import io.github.trialiya.kb.model.project.ProjectOptions;
import io.github.trialiya.kb.model.tool.ToolCallDetail;
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
import io.github.trialiya.kb.tools.ToolInvocationCollector;
import jakarta.annotation.Nonnull;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/chats")
@Slf4j
public class ChatController {

    private final ChatModelProperties chatModelProperties;
    private final ChatModeProperties chatModeProperties;
    private final RunOptionsResolver runOptions;
    private final PendingMessageService pendingMessages;
    private final ChatClientRegistry chatClients;
    private final ChatTopicRepository chatTopicRepository;
    private final ChatHistoryService chatHistory;
    private final ToolCallService toolCallService;
    private final ChatSearchService chatSearchService;
    private final ChatRunService chatRunService;
    private final CompactService compactService;
    private final ChatEventService chatEventService;
    private final ScriptGuideService scriptGuideService;
    private final ContextItemService contextItemService;
    private final ChatTopicService chatTopicService;
    private final GitRegistry gitRegistry;
    private final SystemPromptService systemPromptService;
    private final ProjectPromptService projectPromptService;

    /** Часы аудита Spring Data — ими же датируется «тронуть чат», см. JdbcConfig#clock. */
    private final Clock clock;

    public ChatController(
            ChatModelProperties chatModelProperties,
            ChatModeProperties chatModeProperties,
            RunOptionsResolver runOptions,
            PendingMessageService pendingMessages,
            ChatClientRegistry chatClients,
            ChatTopicRepository chatTopicRepository,
            ChatHistoryService chatHistory,
            ToolCallService toolCallService,
            ChatSearchService chatSearchService,
            ChatRunService chatRunService,
            CompactService compactService,
            ChatEventService chatEventService,
            ScriptGuideService scriptGuideService,
            ContextItemService contextItemService,
            ChatTopicService chatTopicService,
            GitRegistry gitRegistry,
            SystemPromptService systemPromptService,
            ProjectPromptService projectPromptService,
            Clock clock) {
        this.chatModelProperties = chatModelProperties;
        this.chatModeProperties = chatModeProperties;
        this.runOptions = runOptions;
        this.pendingMessages = pendingMessages;
        this.chatClients = chatClients;
        this.chatTopicRepository = chatTopicRepository;
        this.chatHistory = chatHistory;
        this.toolCallService = toolCallService;
        this.chatSearchService = chatSearchService;
        this.chatRunService = chatRunService;
        this.compactService = compactService;
        this.chatEventService = chatEventService;
        this.scriptGuideService = scriptGuideService;
        this.contextItemService = contextItemService;
        this.chatTopicService = chatTopicService;
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
                                // Пустой текст обычно значит «служебный ряд, показывать нечего», но
                                // у ряда git-команды весь смысл в мете: выбросив его здесь, эта
                                // проекция рассказывала бы историю без pull'а, который посреди
                                // разговора сдвинул ветку, — а GET /messages с ним.
                                .filter(
                                        a ->
                                                (a.getText() != null && !a.getText().isBlank())
                                                        || (a.getMeta() != null
                                                                && a.getMeta().gitEvent() != null))
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
                                                e.getMeta() != null ? e.getMeta().model() : null,
                                                e.getMeta() != null ? e.getMeta().compact() : null,
                                                e.getMeta() != null ? e.getMeta().gitEvent() : null,
                                                e.getMeta() != null && e.getMeta().interjection()
                                                        ? Boolean.TRUE
                                                        : null))
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
        final ChatRunService.RunOptions options =
                runOptions.resolve(conversationId, model, mode, project);
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
                                                                options.project(),
                                                                chatHistory.earlierProjects(
                                                                        conversationId))))
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
                        runOptions.resolve(conversationId, model, mode, project),
                        clientMsgId);
        return Map.of("runId", started.runId(), "messageId", started.userMessageId());
    }

    /**
     * Ставит сообщение в очередь идущего прогона: пользователь пишет, не дожидаясь, пока модель
     * закончит. Ответ — {@code 202}: сообщение принято и сохранено, но в историю чата ещё не
     * попало. Доставит его {@code PendingMessageService} в ближайшем безопасном месте — между
     * итерациями tool-цикла, а если такого не случится, то в конце прогона.
     *
     * <p>Своим эндпоинтом, а не флагом на {@code POST /runs}: у этого запроса другой ответ (нет ни
     * {@code runId}, ни id сообщения — ряда истории ещё нет) и другая проверка (нужен активный
     * прогон, а не свободный чат). {@code runId} — в пути, чтобы очередь не пополнилась под уже
     * сменившийся прогон: вкладка могла узнать о завершении позже, чем нажали «отправить».
     *
     * @return {@code 409}, если этот прогон уже не генерирует, — фронт повторяет обычным {@code
     *     POST /runs}
     */
    @PostMapping("/{conversationId}/runs/{runId}/messages")
    @ResponseStatus(ACCEPTED)
    public void queueMessage(
            @PathVariable final String conversationId,
            @PathVariable final String runId,
            @RequestParam(name = "model", required = false) final String model,
            @RequestParam(name = "mode", required = false) final String mode,
            @RequestParam(name = "project", required = false) final String project,
            @RequestParam(name = "clientMsgId", required = false) final String clientMsgId,
            @RequestBody final StartRunRequest body) {
        if (!StringUtils.hasText(body.text())) {
            throw new ResponseStatusException(BAD_REQUEST, "Empty message");
        }
        verifyOwnerIfPresent(conversationId);
        // Выбор запоминаем как есть, не резолвя: резолв пишет в chat_topic и считает смену
        // проекта, а этому сообщению до собственного прогона ещё дожить надо (см.
        // ChatRunService#deliverQueued). Проверить существование названного — здесь: приняв
        // несуществующую модель, отказать пришлось бы уже некому.
        runOptions.validate(model, mode, project);
        // Приложенное проверяем ДО постановки в очередь — 404 на чужое вложение не должен
        // оставлять за собой принятое сообщение.
        final List<ContextItem> contextItems =
                contextItemService.resolve(conversationId, body.contextItems());
        if (!chatRunService.isGenerating(conversationId, runId)) {
            throw new ResponseStatusException(CONFLICT, "This run is no longer generating");
        }
        pendingMessages.enqueue(
                conversationId,
                getUser(),
                body.text(),
                contextItems,
                new PendingMessageService.PendingOptions(model, mode, project),
                runId,
                clientMsgId);
        chatTopicRepository.updateUpdatedAt(conversationId, LocalDateTime.now(clock));
    }

    /**
     * Сжимает контекст чата по команде {@code /compact} и сразу возвращает {@code runId}: раунд
     * идёт по всему живому окну и живёт десятки секунд, поэтому ответ на этот запрос — только
     * заявка, а исход приезжает событиями {@code COMPACT_DONE}/{@code COMPACT_ERROR} (см. {@link
     * #events}). Пока он идёт, чат занят так же, как на генерации: вопрос в него получит 409.
     *
     * <p>Команда сохраняется обычным сообщением — как и любая реплика, она остаётся в истории, — но
     * в модель, которая сжимает контекст, не попадает: там вместо неё инструкция сжатия (см. {@link
     * CompactService}). Модель — та же, на которой работает чат: окно, которое она несла до сих
     * пор, ей же и предстоит прочитать целиком.
     *
     * @param body {@link CompactRequest} — сообщение целиком; {@code text} обязателен, {@code
     *     instructions} — необязательный хвост-фокус
     * @param clientMsgId id вкладки-отправителя, тот же смысл, что у {@code POST /runs}
     */
    @PostMapping("/{conversationId}/compact")
    public Map<String, Object> compact(
            @PathVariable final String conversationId,
            @RequestParam(name = "clientMsgId", required = false) final String clientMsgId,
            @RequestBody final CompactRequest body) {
        if (!StringUtils.hasText(body.text())) {
            throw new ResponseStatusException(BAD_REQUEST, "Empty message");
        }
        // Не checkChat: у команды нет смысла в ещё не заведённом чате, поэтому здесь строгие
        // 404/403, а не заведение чата на лету.
        final ChatTopicEntity topic = getChatTopic(conversationId);
        final String model = runOptions.resolveModel(conversationId, Optional.of(topic), null);
        final CompactService.StartedCompact started =
                compactService.start(
                        conversationId, body.text(), body.instructions(), model, clientMsgId);
        // Строго после start: 409/422 не сохраняют сообщения, и поднимать за них чат в списке
        // не за что. Успех же дописал в чат обычную реплику — как и любая, она его освежает.
        chatTopicRepository.updateUpdatedAt(conversationId, LocalDateTime.now(clock));
        return Map.of("runId", started.runId(), "messageId", started.messageId());
    }

    /**
     * Детали одного сжатия — числа с его плашки и текст получившейся сводки. Отдельным запросом, а
     * не полем страницы истории: сводка бывает в десятки килобайт, а открывают её изредка и по
     * одной.
     *
     * @param messageId id строки-плашки «контекст сжат» (см. {@code COMPACT_DONE} и поле {@code
     *     compact} сообщения в {@code GET /messages})
     */
    @GetMapping("/{conversationId}/compact")
    public ResponseEntity<CompactDetail> getCompactDetail(
            @PathVariable String conversationId, @RequestParam long messageId) {
        verifyOwnerIfPresent(conversationId);
        return compactService
                .detail(conversationId, messageId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
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
                meta != null ? meta.model() : null,
                meta != null ? meta.compact() : null,
                meta != null ? meta.gitEvent() : null,
                meta != null && meta.interjection() ? Boolean.TRUE : null);
    }

    /** «Крошка» вызовов инструментов — служебное сообщение, которое не показываем пользователю. */
    private static boolean isToolCalls(ChatMessageEntity entity) {
        return entity.getMeta() != null && entity.getMeta().toolCalls();
    }
}
