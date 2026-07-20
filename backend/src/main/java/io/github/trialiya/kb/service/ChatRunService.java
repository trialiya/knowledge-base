package io.github.trialiya.kb.service;

import static io.github.trialiya.kb.advisor.ToolPreparingAdvisor.RUN_ID_PARAM;
import static io.github.trialiya.kb.model.chat.dto.ChatEventType.RUN_DONE;
import static io.github.trialiya.kb.model.chat.dto.ChatEventType.RUN_ERROR;
import static io.github.trialiya.kb.model.chat.dto.ChatEventType.RUN_STARTED;
import static io.github.trialiya.kb.model.chat.dto.ChatEventType.RUN_STOPPED;
import static io.github.trialiya.kb.model.chat.dto.ChatEventType.STREAM;
import static io.github.trialiya.kb.model.chat.dto.ChatEventType.TOOL_CALLS;
import static io.github.trialiya.kb.model.chat.dto.ChatEventType.USER_MESSAGE;

import com.openai.models.chat.completions.ChatCompletion;
import io.github.trialiya.kb.model.chat.dto.ChatEventType;
import io.github.trialiya.kb.model.chat.dto.StreamMessage;
import io.github.trialiya.kb.model.chat.dto.ToolCallsMessage;
import io.github.trialiya.kb.model.chat.dto.UserMessagePayload;
import io.github.trialiya.kb.model.tool.ToolInvocationMeta;
import io.github.trialiya.kb.tools.ToolInvocationCollector;
import io.github.trialiya.kb.utils.ChatUtils;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AbstractMessage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.Disposable;
import reactor.core.publisher.SignalType;

/**
 * Запускает генерацию ответа как фоновую задачу, независимую от HTTP-запроса, и транслирует её ход
 * в {@link ChatEventService}. Благодаря этому: ответ продолжает генерироваться после перезагрузки
 * страницы, его видят все вкладки, а остановка — это явный сигнал, а не разрыв соединения.
 */
@Slf4j
@Service
public class ChatRunService {

    /** Пометки в конце сохранённого оборванного ответа (видно после reload). */
    private static final String STOPPED_MARKER = "[stopped]";

    private static final String ERROR_MARKER = "[error]";

    public static final String _UNKNOWN_FINISH_REASON =
            ChatCompletion.Choice.FinishReason.Value._UNKNOWN.name();

    /** finishReason чанка-границы tool-цикла (Spring AI отдаёт его в верхнем регистре). */
    private static final String TOOL_CALLS_FINISH_REASON =
            ChatCompletion.Choice.FinishReason.Value.TOOL_CALLS.name();

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    private final ChatMemoryService chatMemoryService;
    private final SummarizeService summarizeService;
    private final ChatEventService events;
    private final Executor executor;

    /** runId -&gt; дескриптор активного прогона (для остановки). */
    private final ConcurrentHashMap<String, RunHandle> runs = new ConcurrentHashMap<>();

    /** conversationId -&gt; runId: гарантирует не более одного активного прогона на чат. */
    private final ConcurrentHashMap<String, String> activeByConversation =
            new ConcurrentHashMap<>();

    public ChatRunService(
            ChatClient chatClient,
            ChatMemory chatMemory,
            ChatMemoryService chatMemoryService,
            SummarizeService summarizeService,
            ChatEventService events,
            @Qualifier("chatRunExecutor") Executor executor) {
        this.chatClient = chatClient;
        this.chatMemory = chatMemory;
        this.chatMemoryService = chatMemoryService;
        this.summarizeService = summarizeService;
        this.events = events;
        this.executor = executor;
    }

    private record RunHandle(
            String runId,
            String conversationId,
            String user,
            AtomicReference<Disposable> disposable,
            AtomicBoolean persisted) {}

    /** Запускает генерацию в фоне и сразу возвращает runId — HTTP-запрос не держим. */
    public String start(
            String conversationId,
            String user,
            String userMessage,
            @Nullable String resolvedModel,
            String modeInstructions,
            String clientMsgId) {
        final String runId = UUID.randomUUID().toString();
        // Атомарная заявка на чат: если генерация уже идёт (в т.ч. из другой вкладки) — 409,
        // фронт предложит дождаться или остановить текущую.
        if (activeByConversation.putIfAbsent(conversationId, runId) != null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "A response is already being generated for this chat");
        }
        final RunHandle handle =
                new RunHandle(
                        runId, conversationId, user, new AtomicReference<>(), new AtomicBoolean());
        runs.put(runId, handle);
        events.startRun(conversationId, runId);
        // executor — DelegatingSecurityContextExecutorService: проставит SecurityContext текущего
        // пользователя на worker-поток. Операторы Reactor-стрима исполняются на ДРУГИХ потоках,
        // куда thread-local контекст не доезжает, поэтому пользователя для инструментов передаём
        // ещё и явно — через toolContext (см. buildContext ниже).
        try {
            executor.execute(
                    () -> run(handle, userMessage, resolvedModel, modeInstructions, clientMsgId));
        } catch (RuntimeException e) {
            // например, RejectedExecutionException при остановке пула — не оставляем чат «занятым».
            cleanup(handle);
            throw e;
        }
        return runId;
    }

    /** Останавливает прогон: dispose → CANCEL → частичное сохранение + событие RUN_STOPPED. */
    public boolean stop(String conversationId, String runId) {
        final RunHandle handle = runs.get(runId);
        if (handle == null || !handle.conversationId().equals(conversationId)) {
            return false;
        }
        final Disposable disposable = handle.disposable().get();
        if (disposable != null && !disposable.isDisposed()) {
            disposable.dispose();
        }
        return true;
    }

    public Optional<String> activeRun(String conversationId) {
        return events.activeRunId(conversationId);
    }

    /** Число прогонов в реестре — для мониторинга утечек (см. ChatRuntimeMonitor). */
    public int activeRunCount() {
        return runs.size();
    }

    /** Число чатов с удержанной заявкой — в норме совпадает с {@link #activeRunCount()}. */
    public int claimedConversationCount() {
        return activeByConversation.size();
    }

    private void run(
            RunHandle handle,
            String userMessage,
            @Nullable String resolvedModel,
            String modeInstructions,
            String clientMsgId) {
        final String conversationId = handle.conversationId();
        final String runId = handle.runId();
        final StringBuffer buffer = new StringBuffer();
        final Consumer<Object> liveSink =
                payload -> events.publish(conversationId, eventType(payload), runId, null, payload);
        // Инструмент пошёл — значит, итерация стрима завершилась и её сегмент уже сохранён
        // advisor-цепочкой. Сбрасываем буфер здесь: это надёжная граница, в отличие от
        // finishReason=TOOL_CALLS (агрегированный tool-чанк с ним ToolCallingAdvisor
        // отфильтровывает из потока и до onNext он не доходит). Сами live-события TOOL_CALL
        // публикует ChatMemoryService.saveAll при сохранении tool-данных сегмента.
        final ToolInvocationCollector toolCollector =
                new ToolInvocationCollector(() -> buffer.setLength(0));

        events.publish(
                conversationId,
                USER_MESSAGE,
                runId,
                clientMsgId,
                new UserMessagePayload(userMessage, LocalDateTime.now()));
        events.publish(conversationId, RUN_STARTED, runId, clientMsgId, null);

        try {
            // Прошлый прогон могли оборвать во время выполнения инструментов (в т.ч. падением
            // процесса) — тогда в хвосте истории висит assistant.tool_calls без TOOL-ответа,
            // и модель отвергла бы такой диалог. Достраиваем пару до старта.
            chatMemoryService.repairDanglingToolCalls(conversationId);
            ChatClient.ChatClientRequestSpec spec =
                    chatClient
                            .prompt()
                            .system(sp -> sp.param("mode_instructions", modeInstructions))
                            .user(userMessage)
                            .toolContext(
                                    ChatUtils.buildContext(
                                            conversationId, toolCollector, handle.user()))
                            .advisors(
                                    a ->
                                            a.param(ChatMemory.CONVERSATION_ID, conversationId)
                                                    .param(RUN_ID_PARAM, runId));
            if (resolvedModel != null) {
                spec = spec.options(OpenAiChatOptions.builder().model(resolvedModel));
            }

            final Disposable disposable =
                    spec.stream()
                            .chatResponse()
                            .doFinally(signal -> onTerminal(handle, buffer, toolCollector, signal))
                            .subscribe(
                                    response ->
                                            onNext(
                                                    conversationId,
                                                    runId,
                                                    buffer,
                                                    liveSink,
                                                    response),
                                    error -> log.error("Stream error {}", conversationId, error),
                                    () -> onComplete(handle, toolCollector, liveSink));
            handle.disposable().set(disposable);
        } catch (Exception e) {
            log.error("Failed to run {}", conversationId, e);
            events.publish(
                    conversationId, RUN_ERROR, runId, null, Map.of("message", "start failed"));
            cleanup(handle);
        }
    }

    private void onNext(
            String conversationId,
            String runId,
            StringBuffer buffer,
            Consumer<Object> liveSink,
            ChatResponse response) {
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
                        .map(ChatGenerationMetadata::getFinishReason)
                        .filter(Predicate.not(_UNKNOWN_FINISH_REASON::equals))
                        .orElse(null);
        if (!chunk.isEmpty()) {
            buffer.append(chunk);
        }
        // Граница сегмента: текст до вызова инструментов уже сохранён advisor-цепочкой
        // (MessageChatMemoryAdvisor внутри tool-цикла), в буфере держим только хвост
        // текущего сегмента — иначе persistPartial задублировал бы сохранённые сегменты.
        if (TOOL_CALLS_FINISH_REASON.equals(finishReason)) {
            buffer.setLength(0);
        }
        liveSink.accept(new StreamMessage(chunk, finishReason));
        printUsageStatistics(conversationId, response, finishReason);
    }

    private void onComplete(
            RunHandle handle, ToolInvocationCollector toolCollector, Consumer<Object> liveSink) {
        handle.persisted().set(true);
        // Персист сначала, затем live-событие с уже персистнутыми metas (messageId/callId/
        // responseMessageId в них есть только после attachRunMeta — она же вырезает SKIP_TOOLS,
        // так что после перезагрузки они не покажутся, а тут — так же, одним и тем же списком).
        final List<ToolInvocationMeta> metas =
                chatMemoryService.attachRunMeta(
                        handle.conversationId(), handle.runId(), toolCollector.completedSnapshot());
        liveSink.accept(new ToolCallsMessage(metas));
        events.publish(handle.conversationId(), RUN_DONE, handle.runId(), null, null);
        summarizeService.trySummarize(handle.conversationId());
    }

    /**
     * Терминальная обработка (после onComplete/onError/cancel). На прерывание и ошибку спасаем
     * накопленный текст и сообщаем вкладкам; в любом случае снимаем прогон с учёта.
     */
    private void onTerminal(
            RunHandle handle,
            StringBuffer buffer,
            ToolInvocationCollector toolCollector,
            SignalType signal) {
        if (signal == SignalType.CANCEL) {
            persistPartial(handle, buffer, toolCollector, STOPPED_MARKER);
            events.publish(handle.conversationId(), RUN_STOPPED, handle.runId(), null, null);
        } else if (signal == SignalType.ON_ERROR) {
            persistPartial(handle, buffer, toolCollector, ERROR_MARKER);
            events.publish(
                    handle.conversationId(),
                    RUN_ERROR,
                    handle.runId(),
                    null,
                    Map.of("message", "stream error"));
        }
        cleanup(handle);
    }

    private void cleanup(RunHandle handle) {
        // Сначала закрываем хаб прогона, и только потом снимаем заявку на чат. Иначе новый прогон
        // мог бы стартовать (заявка свободна) и записаться в хаб, который этот cleanup как раз
        // закрывает, — событие новой генерации потерялось бы.
        events.endRun(handle.conversationId(), handle.runId());
        runs.remove(handle.runId());
        activeByConversation.remove(handle.conversationId(), handle.runId());
    }

    private void persistPartial(
            RunHandle handle,
            StringBuffer buffer,
            ToolInvocationCollector toolCollector,
            String marker) {
        if (!handle.persisted().compareAndSet(false, true)) {
            // Уже сохранили (onError + doFinally могут прийти оба).
            return;
        }
        final String conversationId = handle.conversationId();
        // В буфере — только хвост текущего сегмента: завершённые сегменты (и их tool-сообщения)
        // advisor-цепочка уже сохранила по ходу прогона (см. onNext).
        final String partial = buffer.toString().strip();
        try {
            // Прервали во время выполнения инструментов — хвостовой assistant.tool_calls
            // остался без TOOL-ответа; достраиваем пару СТРОГО ДО записи частичного текста:
            // repairDanglingToolCalls смотрит только на последнюю строку, и записанный первым
            // partial навсегда спрятал бы от него оборванную пару (модель отвечала бы 400 на
            // каждый следующий запрос этого чата).
            chatMemoryService.repairDanglingToolCalls(conversationId);
            if (!partial.isBlank()) {
                // Помечаем сохранённый ответ как оборванный — чтобы после reload было видно,
                // что генерацию остановили/она упала, а не получился полный ответ.
                chatMemory.add(conversationId, new AssistantMessage(partial + "\n\n" + marker));
                log.info("Saved partial reply for {} ({} chars)", conversationId, partial.length());
            }
            chatMemoryService.attachRunMeta(
                    conversationId, handle.runId(), toolCollector.completedSnapshot());
        } catch (Exception e) {
            log.warn("Failed to persist partial reply for {}", conversationId, e);
        }
    }

    private void printUsageStatistics(
            String conversationId, ChatResponse response, @Nullable String finishReason) {
        if (finishReason == null
                || finishReason.isEmpty()
                || finishReason.equals(_UNKNOWN_FINISH_REASON)) {
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

    private static ChatEventType eventType(Object payload) {
        return switch (payload) {
            case ToolCallsMessage _ -> TOOL_CALLS;
            default -> STREAM;
        };
    }
}
