package io.github.trialiya.kb.controller;

import static io.github.trialiya.kb.utils.ChatUtils.buildContext;
import static io.github.trialiya.kb.utils.ChatUtils.getUser;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;

import io.github.trialiya.kb.model.chat.dto.Chat;
import io.github.trialiya.kb.model.chat.dto.ChatMessage;
import io.github.trialiya.kb.model.chat.dto.CreateJiraChatRequest;
import io.github.trialiya.kb.model.chat.dto.StreamMessage;
import io.github.trialiya.kb.model.chat.dto.ToolCallMessage;
import io.github.trialiya.kb.model.chat.dto.ToolCallsMessage;
import io.github.trialiya.kb.model.chat.entity.ChatTopicEntity;
import io.github.trialiya.kb.repository.ChatTopicRepository;
import io.github.trialiya.kb.service.ChatMemoryService;
import io.github.trialiya.kb.service.JiraChatService;
import io.github.trialiya.kb.service.SummarizeService;
import io.github.trialiya.kb.tools.ToolInvocationCollector;
import jakarta.annotation.Nonnull;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Predicate;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AbstractMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

@RestController
@RequestMapping("/api/chat")
@Slf4j
public class ChatController {

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    private final ChatTopicRepository chatTopicRepository;
    private final ChatMemoryService chatMemoryService;
    private final SummarizeService summarizeService;
    private final JiraChatService jiraChatService;

    public ChatController(
            ChatClient chatClient,
            ChatMemory chatMemory,
            ChatTopicRepository chatTopicRepository,
            ChatMemoryService chatMemoryService,
            SummarizeService summarizeService,
            JiraChatService jiraChatService) {
        this.chatClient = chatClient;
        this.chatMemory = chatMemory;
        this.chatTopicRepository = chatTopicRepository;
        this.chatMemoryService = chatMemoryService;
        this.summarizeService = summarizeService;
        this.jiraChatService = jiraChatService;
    }

    @PostMapping(value = "/streamTest", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatTestStream(
            @RequestBody final String userMessage,
            @RequestParam("conversationId") final String conversationId) {
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
                                if (j % 2 == 0) {
                                    liveSink.accept(new StreamMessage("\n\n", null));
                                }
                            }
                            emitter.complete();
                        });
        return emitter;
    }

    private static void testToolCalls(
            Consumer<Object> liveSink, String name, Map<Object, Object> arguments) {
        liveSink.accept(
                new ToolCallMessage(
                        new ToolInvocationCollector.ToolInvocation(
                                name,
                                arguments,
                                ToolInvocationCollector.ToolInvocationStatus.STARTED,
                                null)));
        try {
            TimeUnit.MILLISECONDS.sleep(400);
        } catch (InterruptedException e) {
            // nothing - testing
        }
        liveSink.accept(
                new ToolCallMessage(
                        new ToolInvocationCollector.ToolInvocation(
                                name,
                                arguments,
                                ToolInvocationCollector.ToolInvocationStatus.OK,
                                null)));
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(
            @RequestBody final String userMessage,
            @RequestParam("conversationId") final String conversationId) {
        checkChat(conversationId, true);

        final SseEmitter emitter = new SseEmitter(Duration.ofMinutes(30).toMillis());
        final AtomicReference<Disposable> disposableRef = new AtomicReference<>();
        final Consumer<Object> liveSink = sendSseEmitterData(emitter);
        final ToolInvocationCollector toolCollector = new ToolInvocationCollector(liveSink);
        try {
            Disposable disposable =
                    chatClient
                            .prompt()
                            .user(userMessage)
                            .toolContext(buildContext(conversationId, toolCollector))
                            .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                            .stream()
                            .chatResponse()
                            .subscribe(
                                    response -> {
                                        printUsageStatistics(conversationId, response);
                                        liveSink.accept(
                                                new StreamMessage(
                                                        Optional.ofNullable(response)
                                                                .map(ChatResponse::getResult)
                                                                .map(Generation::getOutput)
                                                                .map(AbstractMessage::getText)
                                                                .orElse(""),
                                                        Optional.ofNullable(response)
                                                                .map(ChatResponse::getResult)
                                                                .map(Generation::getMetadata)
                                                                .map(
                                                                        ChatGenerationMetadata
                                                                                ::getFinishReason)
                                                                .orElse(null)));
                                    },
                                    emitter::completeWithError,
                                    () -> {
                                        liveSink.accept(
                                                new ToolCallsMessage(
                                                        toolCollector.completedSnapshot()));
                                        liveSink.accept(new StreamMessage("", "DONE"));
                                        summarizeService.trySummarize(conversationId);
                                        emitter.complete();
                                    });

            disposableRef.set(disposable);
            emitter.onTimeout(
                    () -> {
                        log.warn("SSE timeout for conversation {}", conversationId);
                        Disposable d = disposableRef.get();
                        if (d != null && !d.isDisposed()) {
                            d.dispose();
                        }
                        emitter.complete();
                    });
            emitter.onError(
                    (ex) -> {
                        log.error("SSE error for conversation {}", conversationId, ex);
                        Disposable d = disposableRef.get();
                        if (d != null && !d.isDisposed()) {
                            d.dispose();
                        }
                    });
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
        return emitter;
    }

    @PostMapping
    public List<String> chat(
            @RequestBody String userMessage,
            @RequestParam("conversationId") final String conversationId) {
        checkChat(conversationId, true);
        final ToolInvocationCollector toolCollector = new ToolInvocationCollector();

        ChatResponse chatResponse =
                chatClient
                        .prompt()
                        .user(userMessage)
                        .toolContext(buildContext(conversationId, toolCollector))
                        .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                        .call()
                        .chatResponse();

        return Optional.ofNullable(chatResponse).map(ChatResponse::getResults).stream()
                .flatMap(Collection::stream)
                .map(generation -> generation.getOutput().getText())
                .toList();
    }

    @GetMapping
    public List<Chat> chats() {
        return chatTopicRepository.findAllByUserOrderByUpdatedAtDesc(getUser()).stream()
                .map(
                        chatTopicEntity ->
                                new Chat(
                                        chatTopicEntity.getConversationId(),
                                        chatTopicEntity.getUser(),
                                        chatTopicEntity.getTopic(),
                                        chatTopicEntity.getCreatedAt(),
                                        chatTopicEntity.getUpdatedAt(),
                                        null))
                .toList();
    }

    @GetMapping("chat")
    public Chat chatTopic(@RequestParam("conversationId") final String conversationId) {
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
        return new Chat(
                conversationId,
                chatTopicEntity.getUser(),
                chatTopicEntity.getTopic(),
                chatTopicEntity.getCreatedAt(),
                chatTopicEntity.getUpdatedAt(),
                Optional.ofNullable(
                                chatMemoryService.findChatMessageByConversationId(conversationId))
                        .stream()
                        .flatMap(Collection::stream)
                        .filter(a -> a.getText() != null && !a.getText().isBlank())
                        .map(
                                msg ->
                                        new ChatMessage(
                                                msg.getText(),
                                                msg.getMessageType().getValue(),
                                                msg.getCreatedAt()))
                        .toList());
    }

    @DeleteMapping("chat")
    public void deleteChat(@RequestParam("conversationId") final String conversationId) {
        final ChatTopicEntity chatTopicEntity = getChatTopic(conversationId);
        chatTopicRepository.deleteById(chatTopicEntity.getConversationId());
        chatMemory.clear(conversationId);
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

    @PostMapping("topic")
    public void chatTopic(
            @RequestBody String topic,
            @RequestParam("conversationId") final String conversationId) {
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
                                                true)));
    }

    /**
     * Creates a new "JIRA chat" — a conversation pre-loaded with context from a JIRA issue and
     * optionally a Confluence page.
     *
     * <pre>
     * POST /api/chat/jira
     * {
     *   "jiraUrl": "https://instance.atlassian.net/browse/PROJ-123",
     *   "confluenceUrl": "https://instance.atlassian.net/wiki/spaces/.../pages/12345",
     *   "title": "Optional custom title"
     * }
     * </pre>
     *
     * @return the created Chat (with empty messages list)
     */
    @PostMapping("jira")
    public Chat createJiraChat(@RequestBody CreateJiraChatRequest request) {
        return jiraChatService.createJiraChat(request);
    }

    /**
     * Refreshes a JIRA chat by re-fetching issue data and replacing attachments.
     *
     * <pre>
     * POST /api/chat/jira/{conversationId}/refresh
     * Body: "https://instance.atlassian.net/browse/PROJ-123"   (raw JIRA URL string)
     * </pre>
     *
     * @return updated Chat metadata
     */
    @PostMapping("jira/{conversationId}/refresh")
    public Chat refreshJiraChat(@PathVariable String conversationId, @RequestBody String jiraUrl) {
        return jiraChatService.refreshJiraChat(conversationId, jiraUrl.trim());
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

    private void printUsageStatistics(String conversationId, ChatResponse response) {
        Optional.ofNullable(response)
                .map(ChatResponse::getResult)
                .map(Generation::getMetadata)
                .map(ChatGenerationMetadata::getFinishReason)
                .filter(Predicate.not(String::isBlank))
                // reset to response.getMetadata()
                .map(
                        reason -> {
                            log.info("[{}] FinishReason: {}", conversationId, reason);
                            return response.getMetadata();
                        })
                .map(ChatResponseMetadata::getUsage)
                .ifPresent(
                        usage ->
                                log.info(
                                        "[{}] Usage:\n PromptToken: {}\n CompletionTokens: {}\n TotalTokens: {}",
                                        conversationId,
                                        usage.getPromptTokens(),
                                        usage.getCompletionTokens(),
                                        usage.getTotalTokens()));
    }
}
