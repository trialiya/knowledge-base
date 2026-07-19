package io.github.trialiya.kb.controller;

import static io.github.trialiya.kb.utils.ChatUtils.buildContext;
import static io.github.trialiya.kb.utils.ChatUtils.getUser;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;

import io.github.trialiya.kb.config.model.ChatModelProperties;
import io.github.trialiya.kb.model.chat.dto.Chat;
import io.github.trialiya.kb.model.chat.dto.ChatEventType;
import io.github.trialiya.kb.model.chat.dto.ChatMessage;
import io.github.trialiya.kb.model.chat.dto.ChatSearchResult;
import io.github.trialiya.kb.model.chat.dto.CreateJiraChatRequest;
import io.github.trialiya.kb.model.chat.dto.MessagePage;
import io.github.trialiya.kb.model.chat.dto.MessageSearchHit;
import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import io.github.trialiya.kb.model.chat.entity.ChatTopicEntity;
import io.github.trialiya.kb.model.tool.ToolCallDetail;
import io.github.trialiya.kb.repository.ChatTopicRepository;
import io.github.trialiya.kb.service.ChatEventService;
import io.github.trialiya.kb.service.ChatMemoryService;
import io.github.trialiya.kb.service.ChatRunService;
import io.github.trialiya.kb.service.JiraChatService;
import io.github.trialiya.kb.tools.ToolInvocationCollector;
import jakarta.annotation.Nonnull;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
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
    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    private final ChatTopicRepository chatTopicRepository;
    private final ChatMemoryService chatMemoryService;
    private final JiraChatService jiraChatService;
    private final ChatRunService chatRunService;
    private final ChatEventService chatEventService;

    public ChatController(
            ChatModelProperties chatModelProperties,
            ChatClient chatClient,
            ChatMemory chatMemory,
            ChatTopicRepository chatTopicRepository,
            ChatMemoryService chatMemoryService,
            JiraChatService jiraChatService,
            ChatRunService chatRunService,
            ChatEventService chatEventService) {
        this.chatModelProperties = chatModelProperties;
        this.chatClient = chatClient;
        this.chatMemory = chatMemory;
        this.chatTopicRepository = chatTopicRepository;
        this.chatMemoryService = chatMemoryService;
        this.jiraChatService = jiraChatService;
        this.chatRunService = chatRunService;
        this.chatEventService = chatEventService;
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

    /**
     * Поиск чатов текущего пользователя по названию и/или содержимому сообщений (лупа над списком
     * чатов). Объединяет оба вида совпадений по чату.
     */
    @GetMapping("/search")
    public List<ChatSearchResult> searchChats(
            @RequestParam String q, @RequestParam(defaultValue = "20") int limit) {
        int safe = Math.min(Math.max(limit, 1), 50);
        return chatMemoryService.searchChats(getUser(), q, safe);
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
                                                e.getId(),
                                                e.getContent(),
                                                e.getType().name(),
                                                e.getCreatedAt(),
                                                // синтезирует меты из tool_data для сегментов
                                                // без meta.invocations (оборванные/старые прогоны)
                                                chatMemoryService.invocationsFor(
                                                        e, page.messages()),
                                                e.getMeta() != null ? e.getMeta().runId() : null,
                                                isToolCalls(e)))
                        .toList();
        return new MessagePage(dtos, page.hasMore(), page.oldestCursor());
    }

    /** Поиск сообщений внутри одного чата — для локального find-бара (Ctrl+F). */
    @GetMapping("/{conversationId}/messages/search")
    public List<MessageSearchHit> searchMessages(
            @PathVariable String conversationId, @RequestParam String q) {
        getChatTopic(conversationId); // 404/403 + проверка владельца
        return chatMemoryService.searchMessages(conversationId, q);
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
        chatMemory.clear(conversationId);
        // Уведомляем открытые на этом чате вкладки (в т.ч. в других браузерах) — они закроют его.
        chatEventService.publishIfPresent(
                conversationId, ChatEventType.CHAT_DELETED, null, null, null);
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
     * Sends a user message and returns the assistant reply as a single JSON response. This is the
     * synchronous, non-streaming path; streaming goes through {@link #startRun} + {@link #events}.
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
     * Запускает генерацию ответа как фоновую задачу и сразу возвращает {@code runId}. Сам ответ
     * приходит не в этом запросе, а потоком событий через {@link #events}. Это и есть развязка
     * «обработка ≠ HTTP-запрос»: ответ переживает перезагрузку страницы и виден всем вкладкам.
     *
     * @param clientMsgId идентификатор клиента — чтобы вкладка-отправитель не задвоила свой
     *     оптимистично показанный пузырь, получив его же эхом
     */
    @PostMapping("/{conversationId}/runs")
    public Map<String, String> startRun(
            @PathVariable final String conversationId,
            @RequestParam(name = "model", required = false) final String model,
            @RequestParam(name = "clientMsgId", required = false) final String clientMsgId,
            @RequestBody final String userMessage) {
        checkChat(conversationId, true);
        final String resolvedModel = resolveModel(conversationId, model);
        final String runId =
                chatRunService.start(
                        conversationId, getUser(), userMessage, resolvedModel, clientMsgId);
        return Map.of("runId", runId);
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
     * Полные детали одного вызова инструмента — точечно по id сообщения-сегмента и протокольному id
     * вызова (см. {@link ChatMemoryService#findToolCallDetail}); {@code responseMessageId}
     * опционален (например, вызов ещё не завершился).
     */
    @GetMapping("/{conversationId}/tool-calls")
    public ResponseEntity<ToolCallDetail> getToolCallDetails(
            @PathVariable String conversationId,
            @RequestParam long messageId,
            @RequestParam(required = false) Long responseMessageId,
            @RequestParam String callId) {
        verifyOwnerIfPresent(conversationId);
        return chatMemoryService
                .findToolCallDetail(conversationId, messageId, responseMessageId, callId)
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
        return new ChatMessage(
                chatMessageEntity.getId(),
                message,
                chatMessageEntity.getMessageType().getValue(),
                chatMessageEntity.getCreatedAt(),
                chatMessageEntity.getInvocations(),
                chatMessageEntity.getMeta() != null ? chatMessageEntity.getMeta().runId() : null,
                isToolCalls(chatMessageEntity));
    }

    /** «Крошка» вызовов инструментов — служебное сообщение, которое не показываем пользователю. */
    private static boolean isToolCalls(ChatMessageEntity entity) {
        return entity.getMeta() != null && entity.getMeta().toolCalls();
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
