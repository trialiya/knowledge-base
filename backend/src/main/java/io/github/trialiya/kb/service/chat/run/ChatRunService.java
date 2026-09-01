package io.github.trialiya.kb.service.chat.run;

import static io.github.trialiya.kb.advisor.AdvisorParams.RUN_ID_PARAM;
import static io.github.trialiya.kb.model.chat.dto.ChatEventType.RUN_DONE;
import static io.github.trialiya.kb.model.chat.dto.ChatEventType.RUN_ERROR;
import static io.github.trialiya.kb.model.chat.dto.ChatEventType.RUN_STARTED;
import static io.github.trialiya.kb.model.chat.dto.ChatEventType.RUN_STOPPED;
import static io.github.trialiya.kb.model.chat.dto.ChatEventType.STREAM;
import static io.github.trialiya.kb.model.chat.dto.ChatEventType.TOOL_CALLS;
import static io.github.trialiya.kb.model.chat.dto.ChatEventType.USER_MESSAGE;

import com.openai.models.chat.completions.ChatCompletion;
import io.github.trialiya.kb.config.ChatClientRegistry;
import io.github.trialiya.kb.config.model.ChatModelProperties;
import io.github.trialiya.kb.model.chat.dto.ChatEventType;
import io.github.trialiya.kb.model.chat.dto.StreamMessage;
import io.github.trialiya.kb.model.chat.dto.ToolCallsMessage;
import io.github.trialiya.kb.model.chat.dto.UserMessagePayload;
import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import io.github.trialiya.kb.model.chat.entity.ContextItem;
import io.github.trialiya.kb.model.chat.entity.RunTokenUsage;
import io.github.trialiya.kb.model.project.ProjectSwitch;
import io.github.trialiya.kb.model.tool.ToolInvocationMeta;
import io.github.trialiya.kb.service.chat.event.ChatEventService;
import io.github.trialiya.kb.service.chat.memory.AutoCompactService;
import io.github.trialiya.kb.service.chat.memory.ChatHistoryService;
import io.github.trialiya.kb.service.chat.memory.CompactService;
import io.github.trialiya.kb.service.chat.memory.PendingSummaryService;
import io.github.trialiya.kb.service.chat.memory.SummarizeService;
import io.github.trialiya.kb.service.chat.prompt.SystemPromptService;
import io.github.trialiya.kb.service.chat.runtime.ConversationSlots;
import io.github.trialiya.kb.service.chat.runtime.RunRegistry;
import io.github.trialiya.kb.service.chat.runtime.RunScope;
import io.github.trialiya.kb.tools.RunCancellation;
import io.github.trialiya.kb.tools.ToolInvocationCollector;
import io.github.trialiya.kb.utils.ChatUtils;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Predicate;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AbstractMessage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
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

    /** Шаг опроса реестра прогонов в {@link #awaitQuiescence}. */
    private static final long QUIESCENCE_POLL_MS = 25;

    private final ChatClientRegistry chatClients;
    private final ChatMemory chatMemory;
    private final ChatHistoryService chatHistory;
    private final SummarizeService summarizeService;
    private final PendingSummaryService pendingSummaries;
    private final AutoCompactService autoCompact;
    private final ChatModelProperties chatModels;
    private final ChatEventService events;
    private final SystemPromptService systemPromptService;
    private final PendingMessageService pendingMessages;
    private final RunOptionsResolver runOptions;
    private final RunRegistry runs;
    private final ConversationSlots slots;
    private final Executor executor;

    /**
     * Прогоны, снятые с учёта, но ещё доделывающие терминальную обработку. Считаются отдельно от
     * {@link #runs}, потому что из реестра прогон уходит ДО доставки очереди (см. {@link
     * #onTerminal}), а доставка пишет в БД: без этого счётчика {@link #awaitQuiescence} на
     * остановке приложения увидел бы пустой реестр и отпустил бы shutdown закрывать пул соединений
     * прямо посреди неё.
     */
    private final AtomicInteger finishing = new AtomicInteger();

    public ChatRunService(
            ChatClientRegistry chatClients,
            ChatMemory chatMemory,
            ChatHistoryService chatHistory,
            SummarizeService summarizeService,
            PendingSummaryService pendingSummaries,
            AutoCompactService autoCompact,
            ChatModelProperties chatModels,
            ChatEventService events,
            SystemPromptService systemPromptService,
            PendingMessageService pendingMessages,
            RunOptionsResolver runOptions,
            RunRegistry runs,
            ConversationSlots slots,
            @Qualifier("chatRunExecutor") Executor executor) {
        this.chatClients = chatClients;
        this.chatMemory = chatMemory;
        this.chatHistory = chatHistory;
        this.summarizeService = summarizeService;
        this.pendingSummaries = pendingSummaries;
        this.autoCompact = autoCompact;
        this.chatModels = chatModels;
        this.events = events;
        this.systemPromptService = systemPromptService;
        this.pendingMessages = pendingMessages;
        this.runOptions = runOptions;
        this.runs = runs;
        this.slots = slots;
        this.executor = executor;
    }

    /**
     * Запускает генерацию в фоне и сразу возвращает runId — HTTP-запрос не держим.
     *
     * @param userMessage вопрос пользователя; {@code null} — это повтор упавшего прогона: нового
     *     сообщения не появляется, ходом становится последний неотвеченный вопрос из истории (см.
     *     {@link ChatHistoryService#unansweredUserMessage}). Повтор поверх начатого ответа модели
     *     запрещён — 422.
     * @param contextItems приложенное к вопросу (вложения) — уже проверенное {@code
     *     ContextItemService}. На повторе игнорируется: контекст записан вместе с сообщением
     * @param options чем этот прогон отличается от дефолтного — модель, режим, проект (см. {@link
     *     RunOptions})
     * @param clientMsgId вкладка-отправитель, чтобы она погасила своё эхо; {@code null} — вкладки
     *     нет вовсе: прогон запущен по очереди сообщений, а не запросом (см. {@link
     *     #deliverQueued})
     */
    public StartedRun start(
            String conversationId,
            String user,
            @Nullable String userMessage,
            List<ContextItem> contextItems,
            RunOptions options,
            @Nullable String clientMsgId) {
        // Заявка на чат: если он уже занят (генерацией из другой вкладки, сжатием контекста,
        // git-командой) — 409, фронт предложит дождаться или остановить текущую. Хаб событий здесь
        // не заводим: RUN_STARTED уходит ниже, уже с сохранённым вопросом на руках.
        final String runId = slots.take(conversationId);
        final ChatMessageEntity userRow;
        try {
            // Прошлый прогон могли оборвать во время выполнения инструментов (в т.ч. падением
            // процесса) — тогда в хвосте истории висит assistant.tool_calls без TOOL-ответа,
            // и модель отвергла бы такой диалог. Достраиваем пару СТРОГО ДО записи вопроса:
            // repairDanglingToolCalls смотрит только на последнюю строку, и записанное первым
            // сообщение пользователя навсегда спрятало бы от неё оборванную пару.
            chatHistory.repairDanglingToolCalls(conversationId);
            // Страховка на случай, когда доставить очередь было некому: процесс упал вместе с
            // прогоном, а восстановление на старте приложения по этому чату не отработало. Строго
            // после ремонта хвоста и строго до записи нового вопроса — иначе доставленное встало
            // бы в истории уже после него, то есть после ответа на него же.
            pendingMessages.flushPlain(conversationId);
            // Момент, когда отложенная сводка обходится дешевле всего: разговор молчал, кэш
            // промпта у провайдера всё равно остыл, а сжатая история нужна уже этому вопросу.
            // Строго до записи вопроса — пауза меряется от последнего ряда чата, и записанный
            // вопрос обнулил бы её сам.
            pendingSummaries.applyIfPaused(conversationId);
            userRow =
                    userMessage != null
                            ? chatHistory.saveUserMessage(
                                    conversationId,
                                    userMessage,
                                    contextItems,
                                    options.canonicalProject(),
                                    options.projectSwitch())
                            // Повтор: вопрос уже в истории, ходом остаётся он же. Проверку делаем
                            // ПОСЛЕ ремонта хвоста — достроенный TOOL-ответ как раз и означает,
                            // что модель уже начала отвечать, и повторять этот ход нельзя.
                            // Проект при повторе выбирают заново, поэтому маркер смены может
                            // появиться и здесь — на том же вопросе.
                            : retried(
                                    chatHistory
                                            .unansweredUserMessage(conversationId)
                                            .orElseThrow(
                                                    () ->
                                                            new ResponseStatusException(
                                                                    HttpStatus
                                                                            .UNPROCESSABLE_CONTENT,
                                                                    "Nothing to retry: the last"
                                                                            + " message is not an"
                                                                            + " unanswered question")),
                                    options);
        } catch (RuntimeException e) {
            // Заявку на чат не удерживаем: генерация так и не началась.
            slots.free(conversationId, runId);
            throw e;
        }
        // Область прогона открывается здесь: всё, что о прогоне знают остальные — токены,
        // нумерация вызовов, подписка на стрим, — живёт ровно столько же, сколько она. С учёта её
        // снимает терминальная обработка (см. onTerminal), а не cleanup: реестр прогон обязан
        // покинуть ДО доставки очереди.
        final RunScope scope =
                runs.open(runId, conversationId, user, chatClients.resolveModelId(options.model()));
        events.startRun(conversationId, runId);
        // executor — DelegatingSecurityContextExecutorService: проставит SecurityContext текущего
        // пользователя на worker-поток. Операторы Reactor-стрима исполняются на ДРУГИХ потоках,
        // куда thread-local контекст не доезжает, поэтому пользователя для инструментов передаём
        // ещё и явно — через toolContext (см. ChatUtils.context ниже).
        try {
            executor.execute(() -> run(scope, userRow, options, clientMsgId));
        } catch (RuntimeException e) {
            // например, RejectedExecutionException при остановке пула — не оставляем чат «занятым».
            // Сообщение пользователя при этом уже сохранено и останется в истории: вопрос без
            // ответа честнее молча потерянного вопроса.
            abort(scope);
            throw e;
        }
        return new StartedRun(runId, userRow.getId());
    }

    /**
     * Вопрос, который повторяют, с маркером смены проекта, если повтор поехал в другой проект:
     * проект при повторе выбирают заново, и «всё выше читано в прежнем репозитории» становится
     * правдой ровно так же, как при новом вопросе.
     */
    private ChatMessageEntity retried(ChatMessageEntity question, RunOptions options) {
        return options.projectSwitch() == null
                ? question
                : chatHistory.markProjectSwitch(question, options.projectSwitch());
    }

    /**
     * Результат запуска: id прогона и id уже сохранённого сообщения пользователя. Второй нужен
     * отправившей вкладке — она гасит своё эхо по {@code clientMsgId} и иначе узнала бы id
     * сообщения только после перезагрузки страницы.
     */
    public record StartedRun(String runId, Long userMessageId) {}

    /**
     * Занятость чата снаружи: id операции и что это за операция.
     *
     * @param elapsedMs сколько миллисекунд она уже идёт — для таймера над полем ввода. По часам
     *     сервера (наружу уходит длительность, а не момент старта: разница часов клиента и сервера
     *     её не портит). Есть и у генерации, и у операции: у первой длительность помнит область
     *     прогона, у второй — сама заявка ({@code ConversationSlots#elapsedMs}). {@code null}
     *     остаётся только на гонке — заявку отдали между двумя чтениями
     * @param replayTruncated лог реплея этого чата успел потерять события текущего прогона (см.
     *     {@code ConversationHub}) — начало ответа вкладке придётся взять из истории, реплей его
     *     уже не принесёт
     */
    public record ActiveRun(
            String runId, Kind kind, @Nullable Long elapsedMs, boolean replayTruncated) {

        /**
         * Что именно держит чат. Различие в том, что вкладке с этим делать: генерацию можно
         * остановить и ей можно писать в очередь, операции — ни того, ни другого.
         */
        public enum Kind {
            /** Ответ модели: своя область прогона ({@link RunScope}), стрим, tool-цикл, очередь. */
            GENERATION,
            /**
             * Всё остальное, что держит чат заявкой без прогона, — сжатие контекста, git-команда,
             * восстановление очереди на старте приложения (см. {@code ConversationSlots#claim}).
             */
            OPERATION
        }
    }

    /**
     * Настройки одного прогона: что выбрано в чате (или передано параметром запроса) поверх
     * дефолтов конфигурации. Собираются в контроллере — см. {@code ChatController#resolveRun}.
     *
     * <p>Записью, а не отдельными параметрами: три из четырёх полей — строки, и две из них
     * (инструкции режима и id проекта) в позиционном вызове меняются местами без единой ошибки
     * компиляции.
     *
     * @param model результат резолва модели; {@code null} — «не переопределять», т.е. модель из
     *     конфигурации
     * @param weakModel {@code ChatModelProperties#isWeak} от {@link #model} — решает, попадёт ли в
     *     системный промпт обучающая половина руководства по скриптам (см. {@code
     *     ScriptGuideService})
     * @param streamUsage {@code ChatModelProperties#streamUsage} от {@link #model} — просить ли у
     *     эндпоинта счётчик токенов (см. {@code TokenUsageAdvisor})
     * @param modeInstructions инструкции выбранного режима; пустая строка — «без режима»
     * @param project id проекта, в котором работают инструменты прогона; {@code null} — дефолтный
     *     проект списка (см. {@code ProjectCatalog})
     * @param canonicalProject тот же проект, но названный: {@link #project}, разрешённый до
     *     канонического id. В истории «не назван» хранить нельзя — базовый штамп первого сообщения
     *     обязан назвать репозиторий, иначе следа проектов у чата не появляется вовсе
     * @param projectSwitch смена проекта относительно предыдущих сообщений чата; {@code null} —
     *     проект тот же. Оседает маркером в meta вопроса (см. {@code
     *     ChatHistoryService#saveUserMessage})
     */
    public record RunOptions(
            @Nullable String model,
            boolean weakModel,
            boolean streamUsage,
            String modeInstructions,
            @Nullable String project,
            String canonicalProject,
            @Nullable ProjectSwitch projectSwitch) {}

    /** Останавливает прогон: dispose → CANCEL → частичное сохранение + событие RUN_STOPPED. */
    public boolean stop(String conversationId, String runId) {
        final Optional<RunScope> scope = generating(conversationId, runId);
        scope.ifPresent(RunScope::cancel);
        return scope.isPresent();
    }

    /**
     * Останавливает все активные прогоны — при остановке приложения (см. {@link
     * ChatRuntimeShutdown}). Возвращает число прогонов, которым послан сигнал.
     */
    public int stopAll() {
        final List<RunScope> snapshot = runs.all();
        snapshot.forEach(RunScope::cancel);
        return snapshot.size();
    }

    /**
     * Ждёт (не дольше {@code timeout}), пока прогоны закончатся ПОЛНОСТЬЮ. Терминальная обработка
     * идёт на других потоках и пишет в БД, поэтому после {@link #stopAll} нужна эта пауза: без неё
     * shutdown закрыл бы пул соединений раньше, чем сохранится оборванный ответ и опустеет очередь
     * сообщений чата. Ждём поэтому не опустевшего реестра, а {@link #finishing}: реестр прогон
     * покидает ещё до доставки очереди.
     *
     * @return {@code true}, если все прогоны успели завершиться
     */
    public boolean awaitQuiescence(Duration timeout) {
        final long deadline = System.nanoTime() + timeout.toNanos();
        while (!runs.isEmpty() || finishing.get() > 0) {
            if (System.nanoTime() - deadline >= 0) {
                return false;
            }
            try {
                Thread.sleep(QUIESCENCE_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return runs.isEmpty() && finishing.get() == 0;
            }
        }
        return true;
    }

    /**
     * Чем занят чат прямо сейчас, каким это видят вкладки (см. {@code
     * ConversationSlots#activeRun}), — состояние, по которому вкладка восстанавливает себя после
     * перезагрузки.
     */
    public Optional<ActiveRun> activeRun(String conversationId) {
        final boolean replayTruncated = events.replayTruncated(conversationId);
        return slots.activeRun(conversationId)
                .map(
                        runId ->
                                runs.find(runId)
                                        .map(
                                                scope ->
                                                        new ActiveRun(
                                                                runId,
                                                                ActiveRun.Kind.GENERATION,
                                                                scope.elapsedMs(),
                                                                replayTruncated))
                                        .orElseGet(
                                                () ->
                                                        new ActiveRun(
                                                                runId,
                                                                kindOutsideRegistry(
                                                                        conversationId, runId),
                                                                // Области прогона нет —
                                                                // длительность
                                                                // помнит сама заявка. Операции это
                                                                // единственный источник, и таймер
                                                                // сжатия живёт им.
                                                                slots.elapsedMs(
                                                                        conversationId, runId),
                                                                replayTruncated)));
    }

    /**
     * Вид занятости, когда прогона в реестре нет. Обычно это операция без прогона ({@code
     * ConversationSlots#claim}), но так же выглядит и генерация в терминальной обработке: реестр
     * она покидает ДО доставки очереди, а заявку на чат отдаёт только в самом конце. Вкладке,
     * попавшей в это окно, «операция» стоила бы заблокированного ввода без кнопки «Стоп» до
     * ближайшего события потока — поэтому вид спрашиваем у заявки, которая помнит, кто её взял.
     */
    private ActiveRun.Kind kindOutsideRegistry(String conversationId, String runId) {
        return slots.holdsGeneration(conversationId, runId)
                ? ActiveRun.Kind.GENERATION
                : ActiveRun.Kind.OPERATION;
    }

    /**
     * Идёт ли прямо сейчас именно генерация — этот прогон в этом чате. Строже, чем {@link
     * #activeRun}: заявку на чат держит и сжатие контекста ({@code ConversationSlots#claim}), а у
     * него нет ни tool-цикла, ни терминальной обработки, то есть некому и опустошить очередь
     * сообщений.
     */
    public boolean isGenerating(String conversationId, String runId) {
        return generating(conversationId, runId).isPresent();
    }

    /** Область прогона, если он идёт именно в этом чате. */
    private Optional<RunScope> generating(String conversationId, String runId) {
        return runs.find(runId).filter(scope -> scope.conversationId().equals(conversationId));
    }

    private void run(
            RunScope scope,
            ChatMessageEntity userRow,
            RunOptions options,
            @Nullable String clientMsgId) {
        final String resolvedModel = options.model();
        final boolean weakModel = options.weakModel();
        final String conversationId = scope.conversationId();
        final String runId = scope.runId();
        final StringBuffer buffer = new StringBuffer();
        final Consumer<Object> liveSink =
                payload -> events.publish(conversationId, eventType(payload), runId, null, payload);
        // Инструмент пошёл — значит, итерация стрима завершилась и её сегмент уже сохранён
        // advisor-цепочкой. Сбрасываем буфер здесь: это надёжная граница, в отличие от
        // finishReason=TOOL_CALLS (агрегированный tool-чанк с ним ToolCallingAdvisor
        // отфильтровывает из потока и до onNext он не доходит). Сами live-события TOOL_CALL
        // публикует ToolCallEventPublisher при сохранении tool-данных сегмента.
        final ToolInvocationCollector toolCollector =
                new ToolInvocationCollector(() -> buffer.setLength(0));
        // Публикация live-событий спрашивает у коллектора исход вызова: в сохранённых tool_data
        // провалившийся вызов выглядит как обычный (см. RunScope#completedCall).
        scope.attachCollector(toolCollector);

        // На повторе сообщение не новое, но событие всё равно нужно: вкладки сверяют пузырь по id
        // и срезают всё, что стоит после него, — так пузырь с ошибкой упавшего прогона исчезает
        // везде, а не только там, где нажали «Повторить».
        events.publish(
                conversationId,
                USER_MESSAGE,
                runId,
                clientMsgId,
                new UserMessagePayload(
                        userRow.getId(),
                        userRow.getContent(),
                        userRow.getCreatedAt(),
                        userRow.getContextItems(),
                        userRow.getMeta() != null ? userRow.getMeta().project() : null,
                        userRow.getMeta() != null ? userRow.getMeta().projectSwitchFrom() : null,
                        null));
        // Модель едет в RUN_STARTED, а не доезжает только после перезагрузки: пузырь помечают все
        // вкладки, включая те, где эту модель не выбирали.
        events.publish(
                conversationId, RUN_STARTED, runId, clientMsgId, Map.of("model", scope.model()));

        try {
            // Последняя проверка перед промптом: окно у предела модели сжимается прямо здесь, и
            // собранный ниже запрос уедет уже сжатым. Место единственно возможное — вопрос уже
            // сохранён (сжатие обязано оставить его живым и знает это по позиции), промпт ещё не
            // собран, а деньги несостоявшегося раунда есть куда деть: область прогона на руках.
            autoCompact.compactIfOversized(
                    conversationId,
                    runId,
                    userRow.getPosition(),
                    chatModels.contextTokens(scope.model()),
                    new CompactService.CompactOptions(
                            resolvedModel,
                            weakModel,
                            options.project(),
                            options.modeInstructions()),
                    scope::addCall);
            // The client, not just the model option, follows the resolved model: an entry of
            // kb.chat.models with its own base-url/api-key is served by a connection of its own.
            ChatClient.ChatClientRequestSpec spec =
                    chatClients
                            .forModel(resolvedModel)
                            .prompt()
                            .system(
                                    sp ->
                                            sp.params(
                                                    systemPromptService.placeholders(
                                                            weakModel,
                                                            options.project(),
                                                            options.modeInstructions())))
                            // Своего .user(...) здесь намеренно нет: вопрос уже сохранён в
                            // истории (см. ChatHistoryService.saveUserMessage), и его подмешает
                            // advisor памяти. Передать его ещё и сюда — значит сохранить вторым
                            // рядом; см. PrePersistedUserMessageTest.
                            .toolContext(
                                    ChatUtils.context(conversationId)
                                            .user(scope.user())
                                            .project(options.project())
                                            .collector(toolCollector)
                                            .cancellation(new RunCancellation(scope::stopRequested))
                                            .build())
                            .advisors(
                                    a ->
                                            a.param(ChatMemory.CONVERSATION_ID, conversationId)
                                                    .param(RUN_ID_PARAM, runId));
            // streamUsage — это stream_options.include_usage: без него OpenAI-совместимый
            // эндпоинт в стриме не присылает usage вовсе, и считать прогону будет нечего
            // (см. TokenUsageAdvisor). Опции ставим и без выбранной модели: на дефолтной прогон
            // обязан считаться так же, а шлюз, который поля не понимает, выключают на своей
            // модели — kb.chat.models[].stream-usage.
            final OpenAiChatOptions.Builder chatOptions = OpenAiChatOptions.builder();
            chatOptions.streamUsage(options.streamUsage());
            if (resolvedModel != null) {
                chatOptions.model(resolvedModel);
            }
            spec = spec.options(chatOptions);

            final Disposable disposable =
                    spec.stream()
                            .chatResponse()
                            .doFinally(signal -> onTerminal(scope, buffer, toolCollector, signal))
                            .subscribe(
                                    response ->
                                            onNext(
                                                    conversationId,
                                                    runId,
                                                    buffer,
                                                    liveSink,
                                                    response),
                                    error -> log.error("Stream error {}", conversationId, error),
                                    () -> onComplete(scope, toolCollector, liveSink));
            // Остановку могли запросить, пока задача ещё не подписалась на стрим, — attach
            // закрывает это окно (см. RunScope.cancel).
            scope.attach(disposable);
        } catch (Exception e) {
            log.error("Failed to run {}", conversationId, e);
            events.publish(
                    conversationId, RUN_ERROR, runId, null, Map.of("message", "start failed"));
            abort(scope);
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
    }

    private void onComplete(
            RunScope scope, ToolInvocationCollector toolCollector, Consumer<Object> liveSink) {
        // Результат не читаем намеренно: за успешно завершившимся прогоном частичного сохранения
        // уже не будет, и заявка нужна только чтобы его не сделал опоздавший терминальный сигнал.
        scope.claimPersist();
        // Персист сначала, затем live-событие с уже персистнутыми metas (callId в них есть только
        // после записи — она же вырезает SKIP_TOOLS, так что после перезагрузки они не покажутся, а
        // тут — так же, одним и тем же списком).
        final List<ToolInvocationMeta> metas =
                chatHistory.markRunResult(
                        scope.conversationId(),
                        scope.runId(),
                        scope.model(),
                        scope.usage(),
                        toolCollector.completedSnapshot());
        liveSink.accept(new ToolCallsMessage(metas));
        events.publish(scope.conversationId(), RUN_DONE, scope.runId(), null, null);
        summarizeService.trySummarize(scope.conversationId());
    }

    /**
     * Терминальная обработка (после onComplete/onError/cancel). На прерывание и ошибку спасаем
     * накопленный текст и сообщаем вкладкам; в любом случае снимаем прогон с учёта.
     */
    private void onTerminal(
            RunScope scope,
            StringBuffer buffer,
            ToolInvocationCollector toolCollector,
            SignalType signal) {
        if (signal == SignalType.CANCEL) {
            persistPartial(scope, buffer, toolCollector, STOPPED_MARKER);
            events.publish(scope.conversationId(), RUN_STOPPED, scope.runId(), null, null);
        } else if (signal == SignalType.ON_ERROR) {
            persistPartial(scope, buffer, toolCollector, ERROR_MARKER);
            events.publish(
                    scope.conversationId(),
                    RUN_ERROR,
                    scope.runId(),
                    null,
                    Map.of("message", "stream error"));
        }
        // Прогон перестаёт считаться генерирующим ДО доставки очереди. Приём сообщения сверяется
        // именно с этим (см. {@link #isGenerating} и {@code ChatController#queueMessage}), и
        // порядок «сначала перестали генерировать, потом опустошили очередь» — то, что не даёт
        // сообщению, принятому на самой границе завершения, остаться в таблице: приём, увидевший
        // прогон живым, гарантированно успел закоммитить строку до этой доставки, а увидевший
        // мёртвым — доставляет сам.
        //
        // Заявку на чат при этом ещё держим — её снимает cleanup, вместе с закрытием хаба и в
        // строго том порядке (см. ConversationSlots#release).
        //
        // Завершающимся прогон считается СТРОГО ДО ухода из реестра: остаток этого метода пишет в
        // БД, а awaitQuiescence на остановке приложения ждёт именно finishing — увидев между двумя
        // строками пустой реестр, shutdown закрыл бы пул соединений посреди доставки.
        finishing.incrementAndGet();
        runs.close(scope.runId());
        try {
            // Сводка, которая ждала паузы, но ждать больше не может: контекст дорос до доли окна
            // модели, за которой разговор дорог сам по себе. Место выбрано замером: здесь на
            // руках `contextTokens` только что законченного прогона — единственное честное
            // «сколько сейчас занимает история».
            pendingSummaries.applyIfOversized(
                    scope.conversationId(),
                    scope.usage().contextTokens(),
                    chatModels.contextTokens(scope.model()));
            // Доставка ДО cleanup ещё и по второй причине: лог событий прогона живёт ровно
            // столько, сколько сам прогон (ConversationHub чистит его в endRun), а опубликованное
            // после закрытия не переживёт переподключения вкладки и вдобавок подняло бы хаб,
            // который уже некому закрыть.
            final PendingMessageService.Flushed flushed = deliverQueued(scope.conversationId());
            cleanup(scope);
            // Автостарт — строго после cleanup: до него заявка на чат ещё удерживается, и прогон
            // по очереди получил бы 409 от самого себя.
            if (flushed.any() && signal == SignalType.ON_COMPLETE) {
                answerQueued(scope.conversationId(), flushed);
            }
        } finally {
            finishing.decrementAndGet();
        }
    }

    /**
     * Доставляет очередь чата, если генерации в нём больше нет. Приём ({@code
     * ChatController#queueMessage}) проверяет прогон до того, как строка окажется в БД, и между
     * проверкой и коммитом прогон успевает завершиться — его собственная доставка тогда застаёт
     * очередь пустой, а второй у него не будет. Эта перепроверка идёт уже после коммита, поэтому
     * окно закрывается; повторной доставки она вызвать не может — строку забирает тот, чей DELETE
     * её застал.
     *
     * <p>Условие — «в чате не генерирует НИКТО», а не «кончился тот самый прогон»: за то же окно
     * другая вкладка успевает начать следующий прогон, и доставка обычным вопросом попала бы в
     * середину чужого хода — между {@code assistant.tool_calls} и ответами инструментов. Живому
     * прогону очередь отдаём молча: её заберёт он сам — advisor-ом в безопасном окне (тогда ещё и с
     * флагом {@code interjection}) или своей терминальной обработкой. Строка при этом не зависает:
     * реестр {@link #runs} прогон покидает ДО собственной доставки, поэтому увидеть его здесь и не
     * дождаться от него доставки нельзя.
     *
     * <p>Доставленное здесь остаётся без ответа — чат предложит «Повторить». Ответ положен за
     * успешно завершившимся прогоном и не положен за остановленным или упавшим (см. {@link
     * #deliverQueued}), а чем кончился тот прогон, отсюда уже не видно: в реестре его нет. Отвечать
     * всем подряд значило бы запускать генерацию после «Стоп» — ровно то, ради чего кнопку и
     * нажимают; молчать в редком окне за успешным прогоном дешевле: сообщение видно в истории, и
     * ответ на него — один клик.
     */
    public void deliverIfNobodyGenerates(String conversationId) {
        if (runs.generatingIn(conversationId)) {
            return;
        }
        deliverQueued(conversationId);
    }

    /**
     * Опустошает очередь сообщений чата обычными вопросами — в конце прогона и на приёме, если
     * генерации в чате уже нет ({@link #deliverIfNobodyGenerates}).
     *
     * <p>Сюда доезжает всё, что advisor не успел забрать: сообщение, отправленное после последнего
     * обращения к модели, и вся очередь, если прогон вообще не дошёл до tool-цикла. Ряды пишутся
     * обычными вопросами, без {@code interjection}: прогон уже кончился, и «это писали, пока ты
     * работал» — про ход, которого больше нет.
     *
     * <p>Доставка сама по себе ничего не запускает — отвечает на неё {@link #answerQueued}, и
     * только за успешно завершившимся прогоном. Оборванный и упавший нового не начинают: остановку
     * нажимают, чтобы генерация прекратилась, а ошибка повторилась бы и на следующем прогоне.
     * Сообщение при этом не теряется — оно записано последним вопросом истории, то есть ровно в том
     * состоянии, где чат предлагает «Повторить» (см. {@link
     * ChatHistoryService#unansweredUserMessage}).
     */
    PendingMessageService.Flushed deliverQueued(String conversationId) {
        try {
            // Настройки приезжают вместе с доставкой: строки очереди она забирает насовсем, и
            // отдельным «подсмотреть до» этот порядок стал бы негласным требованием.
            return pendingMessages.flushPlain(conversationId);
        } catch (RuntimeException e) {
            // Терминальная обработка прогона из-за очереди падать не должна: ответ уже написан
            // и сохранён, а сообщение остаётся в chat_pending_message до следующего повода.
            log.warn("Failed to deliver queued messages for {}", conversationId, e);
            return PendingMessageService.Flushed.NOTHING;
        }
    }

    /**
     * Отвечает на только что доставленную очередь следующим прогоном — тем же путём, что и
     * «Повторить»: нового ряда не заводим, ходом становится доставленный вопрос. Настройки берём из
     * очереди, а не из чата: пользователь мог переключить модель или проект уже после того, как
     * отправил это сообщение.
     */
    private void answerQueued(String conversationId, PendingMessageService.Flushed flushed) {
        final PendingMessageService.PendingOptions queued = flushed.options();
        try {
            start(
                    conversationId,
                    flushed.user(),
                    null,
                    List.of(),
                    runOptions.resolve(
                            conversationId, queued.model(), queued.mode(), queued.project()),
                    null);
        } catch (RuntimeException e) {
            // Чат мог занять другая вкладка между cleanup и этим стартом (409) — вопрос уже в
            // истории, и «Повторить» на нём остаётся.
            log.warn("Failed to answer queued messages for {}", conversationId, e);
        }
    }

    /**
     * Обрывает прогон, до которого не дошла терминальная обработка: отказ пула в {@link #start} и
     * исключение при сборке стрима. Только здесь прогон снимают с учёта вне {@link #onTerminal} —
     * на главном пути реестр он покидает строго до доставки очереди.
     */
    private void abort(RunScope scope) {
        runs.close(scope.runId());
        cleanup(scope);
    }

    private void cleanup(RunScope scope) {
        final RunTokenUsage spent = scope.usage();
        if (!spent.isEmpty()) {
            log.info(
                    "[{}] Run {} left {} tokens of context (+{} from tools, {} generated);"
                            + " billed {} prompt tokens over {} model call(s), {} from cache",
                    scope.conversationId(),
                    scope.runId(),
                    spent.contextTokens(),
                    spent.toolTokens(),
                    spent.outputTokens(),
                    spent.promptTokens(),
                    spent.modelCalls(),
                    spent.cacheReadTokens());
        }
        // Хаб прогона закрывается до снятия заявки — порядок держит release, почему именно он,
        // сказано там же (см. ConversationSlots#release).
        slots.release(scope.conversationId(), scope.runId());
    }

    private void persistPartial(
            RunScope scope,
            StringBuffer buffer,
            ToolInvocationCollector toolCollector,
            String marker) {
        if (!scope.claimPersist()) {
            // Уже сохранили (onError + doFinally могут прийти оба).
            return;
        }
        final String conversationId = scope.conversationId();
        // В буфере — только хвост текущего сегмента: завершённые сегменты (и их tool-сообщения)
        // advisor-цепочка уже сохранила по ходу прогона (см. onNext).
        final String partial = buffer.toString().strip();
        try {
            // Прервали во время выполнения инструментов — хвостовой assistant.tool_calls
            // остался без TOOL-ответа; достраиваем пару СТРОГО ДО записи частичного текста:
            // repairDanglingToolCalls смотрит только на последнюю строку, и записанный первым
            // partial навсегда спрятал бы от него оборванную пару (модель отвечала бы 400 на
            // каждый следующий запрос этого чата).
            chatHistory.repairDanglingToolCalls(conversationId);
            if (!partial.isBlank()) {
                // Помечаем сохранённый ответ как оборванный — чтобы после reload было видно,
                // что генерацию остановили/она упала, а не получился полный ответ.
                chatMemory.add(conversationId, new AssistantMessage(partial + "\n\n" + marker));
                log.info("Saved partial reply for {} ({} chars)", conversationId, partial.length());
            }
        } catch (Exception e) {
            log.warn("Failed to persist partial reply for {}", conversationId, e);
        }
        // Свой try: мета относится и к сегментам, которые advisor-цепочка сохранила ПО ХОДУ
        // прогона, — сорвавшаяся выше запись частичного текста не повод оставить их без плашек
        // и модели. Оборванный ответ тоже кем-то написан, и именно на нём вопрос «какая модель это
        // выдала» задают чаще всего.
        try {
            final List<ToolInvocationMeta> metas =
                    chatHistory.markRunResult(
                            conversationId,
                            scope.runId(),
                            scope.model(),
                            scope.usage(),
                            toolCollector.completedSnapshot());
            // Тот же финальный список, что уходит вкладкам за успешным прогоном (см. onComplete):
            // живые TOOL_CALL-события несут только имя и аргументы, а блоки «изменённые файлы» и
            // «изменённые документы» строятся по resultMeta, которая есть лишь здесь. Без этой
            // публикации остановленный прогон показывал бы блоки лишь после перезагрузки.
            // Строго ДО терминального события: RUN_STOPPED/RUN_ERROR снимают у вкладки метку
            // прогона, а событие TOOL_CALLS не от живого прогона она отбрасывает.
            if (!metas.isEmpty()) {
                events.publish(
                        conversationId,
                        TOOL_CALLS,
                        scope.runId(),
                        null,
                        new ToolCallsMessage(metas));
            }
        } catch (Exception e) {
            log.warn("Failed to attach run meta for {}", conversationId, e);
        }
    }

    private static ChatEventType eventType(Object payload) {
        return switch (payload) {
            case ToolCallsMessage _ -> TOOL_CALLS;
            default -> STREAM;
        };
    }
}
