package io.github.trialiya.kb.controller;

import static io.github.trialiya.kb.utils.ChatUtils.buildContext;
import static io.github.trialiya.kb.utils.ChatUtils.getUser;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;

import io.github.trialiya.kb.model.chat.dto.Chat;
import io.github.trialiya.kb.model.chat.dto.ChatMessage;
import io.github.trialiya.kb.model.chat.dto.CreateJiraChatRequest;
import io.github.trialiya.kb.model.chat.entity.ChatTopic;
import io.github.trialiya.kb.repository.ChatMessageRepository;
import io.github.trialiya.kb.repository.ChatTopicRepository;
import io.github.trialiya.kb.service.ChatMemoryService;
import io.github.trialiya.kb.service.JiraChatService;
import io.github.trialiya.kb.service.SummarizeService;
import jakarta.annotation.Nonnull;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AbstractMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.http.HttpEntity;
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
    private final ChatMessageRepository chatMessageRepository;
    private final ChatMemoryService chatMemoryService;
    private final SummarizeService summarizeService;
    private final JiraChatService jiraChatService;

    public ChatController(
            ChatClient chatClient,
            ChatMemory chatMemory,
            ChatTopicRepository chatTopicRepository,
            ChatMessageRepository chatMessageRepository,
            ChatMemoryService chatMemoryService,
            SummarizeService summarizeService,
            JiraChatService jiraChatService) {
        this.chatClient = chatClient;
        this.chatMemory = chatMemory;
        this.chatTopicRepository = chatTopicRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.chatMemoryService = chatMemoryService;
        this.summarizeService = summarizeService;
        this.jiraChatService = jiraChatService;
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(
            @RequestBody final String userMessage,
            @RequestParam("conversationId") final String conversationId,
            HttpEntity<String> httpEntity) {
        checkChat(conversationId, true);

        final SseEmitter emitter = new SseEmitter(300_000L);
        final AtomicReference<Disposable> disposableRef = new AtomicReference<>();

        try {
            Disposable disposable =
                    chatClient
                            .prompt()
                            .user(userMessage)
                            .toolContext(buildContext(conversationId))
                            .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                            .stream()
                            .chatResponse()
                            .subscribe(
                                    response -> {
                                        try {
                                            emitter.send(
                                                    Map.of(
                                                            "message",
                                                            Optional.ofNullable(response)
                                                                    .map(ChatResponse::getResult)
                                                                    .map(Generation::getOutput)
                                                                    .map(AbstractMessage::getText)
                                                                    .orElse(""),
                                                            "finishReason",
                                                            Optional.ofNullable(response)
                                                                    .map(ChatResponse::getResult)
                                                                    .map(Generation::getMetadata)
                                                                    .map(
                                                                            ChatGenerationMetadata
                                                                                    ::getFinishReason)
                                                                    .orElse("")),
                                                    MediaType.APPLICATION_JSON);
                                        } catch (IOException e) {
                                            emitter.completeWithError(e);
                                        }
                                    },
                                    emitter::completeWithError,
                                    () -> {
                                        try {
                                            emitter.send(
                                                    Map.of(
                                                            "message", "",
                                                            "finishReason", "DONE"));
                                        } catch (IOException e) {
                                            log.error(e.getMessage());
                                        }
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

        ChatResponse chatResponse =
                chatClient
                        .prompt()
                        .user(userMessage)
                        .toolContext(buildContext(conversationId))
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
                        chatTopic ->
                                new Chat(
                                        chatTopic.getConversationId(),
                                        chatTopic.getUser(),
                                        chatTopic.getTopic(),
                                        chatTopic.getCreatedAt(),
                                        chatTopic.getUpdatedAt(),
                                        null))
                .toList();
    }

    @GetMapping("chat")
    public Chat chatTopic(@RequestParam("conversationId") final String conversationId) {
        final ChatTopic chatTopic =
                chatTopicRepository
                        .findById(conversationId)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                NOT_FOUND,
                                                "Not found conversation id " + conversationId));
        if (!chatTopic.getUser().equals(getUser())) {
            throw new ResponseStatusException(FORBIDDEN, "Forbidden");
        }
        return new Chat(
                conversationId,
                chatTopic.getUser(),
                chatTopic.getTopic(),
                chatTopic.getCreatedAt(),
                chatTopic.getUpdatedAt(),
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
        final ChatTopic chatTopic = getChatTopic(conversationId);
        chatTopicRepository.deleteById(chatTopic.getConversationId());
        chatMemory.clear(conversationId);
    }

    private @NonNull ChatTopic getChatTopic(String conversationId) {
        final ChatTopic chatTopic =
                chatTopicRepository
                        .findById(conversationId)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                NOT_FOUND,
                                                "Not found conversation id " + conversationId));
        if (!chatTopic.getUser().equals(getUser())) {
            throw new ResponseStatusException(FORBIDDEN, "Forbidden");
        }
        return chatTopic;
    }

    @PostMapping("topic")
    public void chatTopic(
            @RequestBody String topic,
            @RequestParam("conversationId") final String conversationId) {
        chatTopicRepository
                .findById(conversationId)
                .ifPresentOrElse(
                        chatTopic -> {
                            if (!chatTopic.getUser().equals(getUser())) {
                                throw new ResponseStatusException(FORBIDDEN, "Forbidden");
                            }
                            chatTopicRepository.save(
                                    new ChatTopic(
                                            chatTopic.getConversationId(),
                                            chatTopic.getUser(),
                                            true,
                                            topic,
                                            chatTopic.getCreatedAt(),
                                            chatTopic.getUpdatedAt(),
                                            false));
                        },
                        () ->
                                chatTopicRepository.save(
                                        new ChatTopic(
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
                        chatTopic -> {
                            if (!chatTopic.getUser().equals(getUser())) {
                                throw new ResponseStatusException(FORBIDDEN, "Forbidden");
                            }
                            if (update) {
                                chatTopicRepository.updateUpdatedAt(conversationId);
                            }
                        },
                        () ->
                                chatTopicRepository.save(
                                        new ChatTopic(
                                                conversationId,
                                                getUser(),
                                                false,
                                                "",
                                                null,
                                                null,
                                                true)));
    }
}
