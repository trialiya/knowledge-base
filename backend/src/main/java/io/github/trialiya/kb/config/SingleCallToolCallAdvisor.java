package io.github.trialiya.kb.config;

import static io.github.trialiya.kb.utils.ChatUtils.DEFAULT_CONVERSATION_ID;

import io.github.trialiya.kb.model.chat.entity.ChatMessage;
import io.github.trialiya.kb.repository.ChatMessageRepository;
import io.github.trialiya.kb.repository.ChatTopicRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientMessageAggregator;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.model.tool.internal.ToolCallReactiveContextHolder;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.core.Ordered;
import org.springframework.util.Assert;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

@Slf4j
public class SingleCallToolCallAdvisor extends ToolCallAdvisor {

    private final ChatTopicRepository chatTopicRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final boolean streamToolCallResponses;
    private final String toolName;

    public SingleCallToolCallAdvisor(
            ToolCallingManager manager,
            ChatTopicRepository chatTopicRepository,
            ChatMessageRepository chatMessageRepository,
            String toolName) {
        super(manager, Ordered.HIGHEST_PRECEDENCE + 300, true, true);
        this.chatTopicRepository = chatTopicRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.streamToolCallResponses = true;
        this.toolName = toolName;
    }

    // -------------------------------------------------------------------------
    // Streaming implementation
    // -------------------------------------------------------------------------
    @Override
    public Flux<ChatClientResponse> adviseStream(
            ChatClientRequest chatClientRequest, StreamAdvisorChain streamAdvisorChain) {
        Assert.notNull(streamAdvisorChain, "streamAdvisorChain must not be null");
        Assert.notNull(chatClientRequest, "chatClientRequest must not be null");

        if (chatClientRequest.prompt().getOptions() == null
                || !(chatClientRequest.prompt().getOptions() instanceof ToolCallingChatOptions)) {
            throw new IllegalArgumentException(
                    "ToolCall Advisor requires ToolCallingChatOptions to be set in the ChatClientRequest options.");
        }

        ChatClientRequest initializedRequest =
                this.doInitializeLoopStream(chatClientRequest, streamAdvisorChain);

        // Overwrite the ToolCallingChatOptions to disable internal tool execution.
        // Use the validated options from the original request to satisfy NullAway,
        // as doInitializeLoopStream should preserve the options contract.
        var optionsCopy = (ToolCallingChatOptions) chatClientRequest.prompt().getOptions().copy();
        optionsCopy.setInternalToolExecutionEnabled(false);

        final String conversationId =
                getConversationId(chatClientRequest.context(), DEFAULT_CONVERSATION_ID);
        final boolean skipByMsgPosition =
                chatMessageRepository
                        .findFirstByConversationIdOrderByCreatedAtDesc(conversationId)
                        .map(ChatMessage::getPosition)
                        .map(
                                position -> {
                                    if (position < 8) {
                                        return true;
                                    } else {
                                        return position % 11 == 0 || position % 11 == 1;
                                    }
                                })
                        .orElse(false);
        final AtomicBoolean skipToolCall =
                new AtomicBoolean(
                        skipByMsgPosition
                                || chatTopicRepository
                                        .findById(conversationId)
                                        .filter(
                                                chatTopic ->
                                                        chatTopic.isUser()
                                                                && chatTopic.getTopic() != null
                                                                && !chatTopic.getTopic().isBlank())
                                        .isPresent());

        return this.internalStream(
                streamAdvisorChain,
                initializedRequest,
                optionsCopy,
                initializedRequest.prompt().getInstructions(),
                skipToolCall);
    }

    private Flux<ChatClientResponse> internalStream(
            StreamAdvisorChain streamAdvisorChain,
            ChatClientRequest originalRequest,
            ToolCallingChatOptions optionsCopy,
            List<Message> instructions,
            AtomicBoolean skipToolCall) {

        return Flux.deferContextual(
                contextView -> {
                    ChatOptions effectiveOptions = buildOptions(optionsCopy, skipToolCall);

                    var processedRequest =
                            ChatClientRequest.builder()
                                    .prompt(new Prompt(instructions, effectiveOptions))
                                    .context(originalRequest.context())
                                    .build();

                    processedRequest = this.doBeforeStream(processedRequest, streamAdvisorChain);

                    // Get a copy of the chain excluding this advisor
                    StreamAdvisorChain chainCopy = streamAdvisorChain.copy(this);

                    final ChatClientRequest finalRequest = processedRequest;
                    log.debug("finalRequest: {}", processedRequest);

                    // Get the streaming response
                    Flux<ChatClientResponse> responseFlux = chainCopy.nextStream(processedRequest);

                    // Holder for aggregated response (set when aggregation completes)
                    AtomicReference<ChatClientResponse> aggregatedResponseRef =
                            new AtomicReference<>();

                    return streamWithToolCallResponses(
                            responseFlux,
                            aggregatedResponseRef,
                            finalRequest,
                            streamAdvisorChain,
                            originalRequest,
                            optionsCopy,
                            skipToolCall);
                });
    }

    /**
     * Streams all chunks immediately including intermediate tool call responses. Uses publish() to
     * multicast the stream for parallel streaming and aggregation.
     */
    private Flux<ChatClientResponse> streamWithToolCallResponses(
            Flux<ChatClientResponse> responseFlux,
            AtomicReference<ChatClientResponse> aggregatedResponseRef,
            ChatClientRequest finalRequest,
            StreamAdvisorChain streamAdvisorChain,
            ChatClientRequest originalRequest,
            ToolCallingChatOptions optionsCopy,
            AtomicBoolean skipToolCall) {

        return responseFlux
                .publish(
                        shared -> {
                            // Branch 1: Stream chunks immediately for real-time streaming UX
                            Flux<ChatClientResponse> streamingBranch =
                                    new ChatClientMessageAggregator()
                                            .aggregateChatClientResponse(
                                                    shared, aggregatedResponseRef::set);

                            // Branch 2: After streaming completes, check for tool calls and
                            // potentially recurse.
                            Flux<ChatClientResponse> recursionBranch =
                                    Flux.defer(
                                            () ->
                                                    this.handleToolCallRecursion(
                                                            aggregatedResponseRef.get(),
                                                            finalRequest,
                                                            streamAdvisorChain,
                                                            originalRequest,
                                                            optionsCopy,
                                                            skipToolCall));

                            // Emit all streaming chunks first, then append any recursive results
                            return streamingBranch.concatWith(recursionBranch);
                        })
                .filter(
                        ccr ->
                                this.streamToolCallResponses
                                        || !(ccr.chatResponse() != null
                                                && ccr.chatResponse().hasToolCalls()));
    }

    /**
     * Handles tool call detection and recursion after streaming completes. Returns empty flux if no
     * tool call, or recursive stream if tool call detected.
     */
    private Flux<ChatClientResponse> handleToolCallRecursion(
            ChatClientResponse aggregatedResponse,
            ChatClientRequest finalRequest,
            StreamAdvisorChain streamAdvisorChain,
            ChatClientRequest originalRequest,
            ToolCallingChatOptions optionsCopy,
            AtomicBoolean skipToolCall) {

        if (aggregatedResponse == null) {
            return Flux.empty();
        }

        // === НАЧАЛО ИЗМЕНЕНИЙ ===
        Optional.of(aggregatedResponse)
                .map(ChatClientResponse::chatResponse)
                .map(ChatResponse::getMetadata)
                .map(ChatResponseMetadata::getUsage)
                .filter(usage -> usage.getTotalTokens() > 0)
                .ifPresent(
                        usage -> {
                            log.info("[Tool] Prompt tokens:     " + usage.getPromptTokens());
                            log.info("[Tool] Completion tokens: " + usage.getCompletionTokens());
                            log.info("[Tool] Total tokens:      " + usage.getTotalTokens());
                        });
        // === КОНЕЦ ИЗМЕНЕНИЙ ===

        aggregatedResponse = this.doAfterStream(aggregatedResponse, streamAdvisorChain);

        ChatResponse chatResponse = aggregatedResponse.chatResponse();
        boolean isToolCall = chatResponse != null && chatResponse.hasToolCalls();

        if (!isToolCall) {
            // No tool call - streaming already happened, nothing more to emit
            return this.doFinalizeLoopStream(Flux.empty(), streamAdvisorChain);
        }

        Assert.notNull(
                chatResponse, "redundant check that should never fail, but here to help NullAway");
        final ChatClientResponse finalAggregatedResponse = aggregatedResponse;

        // Execute tool calls on bounded elastic scheduler (tool execution is blocking)
        Flux<ChatClientResponse> toolCallFlux =
                Flux.deferContextual(
                        ctx -> {
                            ToolExecutionResult toolExecutionResult;
                            try {
                                ToolCallReactiveContextHolder.setContext(ctx);
                                toolExecutionResult =
                                        this.toolCallingManager.executeToolCalls(
                                                finalRequest.prompt(), chatResponse);
                            } finally {
                                ToolCallReactiveContextHolder.clearContext();
                            }

                            if (toolExecutionResult.returnDirect()) {
                                // Return tool execution result directly to the application client
                                return Flux.just(
                                        finalAggregatedResponse
                                                .mutate()
                                                .chatResponse(
                                                        ChatResponse.builder()
                                                                .from(chatResponse)
                                                                .generations(
                                                                        ToolExecutionResult
                                                                                .buildGenerations(
                                                                                        toolExecutionResult))
                                                                .build())
                                                .build());
                            } else {
                                // Recursive call with updated conversation history
                                List<Message> nextInstructions =
                                        this.doGetNextInstructionsForToolCallStream(
                                                finalRequest,
                                                finalAggregatedResponse,
                                                toolExecutionResult);
                                return this.internalStream(
                                        streamAdvisorChain,
                                        originalRequest,
                                        optionsCopy,
                                        nextInstructions,
                                        skipToolCall);
                            }
                        });
        return toolCallFlux.subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Строим опции для текущей итерации. Первый раз: принудительно вызываем нужный инструмент.
     * Последующие: обычный режим (LLM сам решает, звать ли инструменты).
     */
    private ChatOptions buildOptions(ToolCallingChatOptions base, AtomicBoolean skipToolCall) {
        if (base instanceof OpenAiChatOptions openAiOptions) {
            OpenAiChatOptions copy = openAiOptions.copy();
            if (!skipToolCall.get()) {
                // Первая итерация — принудительный вызов инструмента
                log.debug("Forcing tool_choice to: {}", toolName);
                copy.setToolChoice(
                        OpenAiApi.ChatCompletionRequest.ToolChoiceBuilder.function(toolName));
                skipToolCall.set(true);
            } else {
                // Последующие итерации — убираем принудительный выбор
                log.debug("Tool already called, using auto tool_choice");
                copy.setToolChoice(OpenAiApi.ChatCompletionRequest.ToolChoiceBuilder.AUTO);
            }
            return copy;
        }
        return base;
    }

    private String getConversationId(Map<String, Object> context, String defaultConversationId) {
        Assert.notNull(context, "context cannot be null");
        Assert.noNullElements(context.keySet().toArray(), "context cannot contain null keys");
        Assert.hasText(defaultConversationId, "defaultConversationId cannot be null or empty");
        return context.containsKey(ChatMemory.CONVERSATION_ID)
                ? context.get(ChatMemory.CONVERSATION_ID).toString()
                : defaultConversationId;
    }
}
