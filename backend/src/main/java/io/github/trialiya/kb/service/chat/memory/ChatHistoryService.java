package io.github.trialiya.kb.service.chat.memory;

import io.github.trialiya.kb.model.chat.dto.MessageCursor;
import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import io.github.trialiya.kb.model.chat.entity.ChatMessageMeta;
import io.github.trialiya.kb.model.chat.entity.ContextItem;
import io.github.trialiya.kb.model.chat.entity.FileRevertMeta;
import io.github.trialiya.kb.model.chat.entity.GitEventMeta;
import io.github.trialiya.kb.model.chat.entity.RunTokenUsage;
import io.github.trialiya.kb.model.chat.spring.IMessage;
import io.github.trialiya.kb.model.chat.spring.UserChatMessage;
import io.github.trialiya.kb.model.project.ProjectSwitch;
import io.github.trialiya.kb.model.tool.ToolData;
import io.github.trialiya.kb.model.tool.ToolInvocation;
import io.github.trialiya.kb.model.tool.ToolInvocationMeta;
import io.github.trialiya.kb.repository.ChatMessageRepository;
import io.github.trialiya.kb.service.chat.context.ContextItemService;
import io.github.trialiya.kb.tools.RecordingToolCallback;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.logging.log4j.util.Strings;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * История одного чата в {@code chat_message}: окно, которое уходит модели, страницы для UI и
 * дозапись новых сообщений. Единственный писатель этой таблицы на горячем пути прогона; {@link
 * ChatHistoryMemory} — тонкая обёртка над ним для Spring AI.
 *
 * <p>Запись строго дописывающая: {@link #append} никогда не удаляет и не переписывает уже
 * сохранённые ряды, поэтому позиция нового ряда — это максимум по чату плюс единица, и никакого
 * «окна» на стороне записи не существует. Что реально уедет модели, решает {@code SummarizeService}
 * (ряды с {@code summarized = true} выпадают из {@link #promptRows}).
 *
 * <p>UI-мета вызовов инструментов и {@code tool_call_index} — не здесь, а в {@link
 * ToolCallService}; протокольные {@code tool_data} пишутся вместе с сообщением, потому что это
 * часть самого сообщения.
 */
@AllArgsConstructor
@Slf4j
@Service
public class ChatHistoryService {

    private final ChatMessageRepository chatMessageRepository;
    private final ContextItemService contextItemService;
    private final ToolCallService toolCalls;
    private final ToolCallEventPublisher toolCallEvents;
    private final ActiveProjectNotice activeProject;

    /** Сообщение + его протокольные tool-данные, извлечённые один раз (см. {@link #toolDataOf}). */
    private record Pending(Message message, @Nullable ToolData toolData) {

        boolean hasToolData() {
            return toolData != null;
        }
    }

    // ── Запись ────────────────────────────────────────────────────────────────

    /**
     * Сохраняет сообщение пользователя ДО обращения к модели — прогон его уже не записывает (см.
     * {@code ChatRunService.start}). Что это даёт: id сообщения известен сразу (его возвращает
     * эндпоинт и несёт событие {@code USER_MESSAGE}), а сам вопрос переживает падение прогона до
     * подписки на стрим.
     *
     * <p>Записанный ряд подхватит advisor памяти как обычную историю, поэтому прогон НЕ передаёт
     * своё {@code .user(...)}: иначе то же сообщение сохранилось бы вторым рядом. Инвариант
     * закреплён в {@code PrePersistedUserMessageTest}.
     *
     * <p>Транзакция обязана закрыться до старта стрима: {@code BaseAdvisor.adviseStream} исполняет
     * {@code before()} через {@code publishOn(scheduler)}, то есть на другом потоке и другом
     * соединении, и незакоммиченную строку он не увидел бы — модель получила бы диалог без
     * последнего вопроса.
     *
     * <p>{@code contextItems} — приложенное к вопросу (вложения); уходит в {@code meta} той же
     * записью, поэтому привязка не требует ни второго запроса, ни знания id заранее.
     *
     * <p>{@code projectSwitch} — этим вопросом чат перешёл в другой проект; тоже оседает в {@code
     * meta} и делает историю выше честной: и промпт (см. {@link ActiveProjectNotice}), и фронт
     * предупредят, что прочитанное раньше относится к прежнему репозиторию. Первому сообщению чата
     * маркер не ставится: над пустой историей предупреждать не о чем, даже когда проект в нём
     * назван явно.
     *
     * <p>Зато первое сообщение получает базовый штамп — {@code project} без «откуда». С него
     * начинается след проектов чата, и без него у истории, которая никуда не переключалась, не
     * осталось бы ни одного носителя: репозиторий пришлось бы каждый раз спрашивать у {@code
     * chat_topic}, на каждой итерации tool-цикла.
     *
     * @param project канонический id проекта, на котором идёт прогон; {@code null} — вызывающему
     *     нечего штамповать (команда {@code /compact}, первым сообщением чата не бывающая)
     */
    @Transactional
    public ChatMessageEntity saveUserMessage(
            String conversationId,
            String text,
            List<ContextItem> contextItems,
            @Nullable String project,
            @Nullable ProjectSwitch projectSwitch) {
        final long position = lastPosition(conversationId) + 1;
        final ProjectSwitch marked = position > 1 ? projectSwitch : null;
        final String stamped = position > 1 ? (marked == null ? null : marked.to()) : project;
        return chatMessageRepository.save(
                new ChatMessageEntity(
                        0,
                        conversationId,
                        text,
                        MessageType.USER,
                        position,
                        false,
                        false,
                        LocalDateTime.now(),
                        ChatMessageMeta.ofUserMessage(
                                contextItems, stamped, marked == null ? null : marked.from())));
    }

    /**
     * Тот же маркер, но на уже записанном вопросе — путь повтора прогона: нового сообщения не
     * появляется, а проект прогона сменился, и предупредить о прежнем репозитории всё равно надо.
     *
     * <p>Если маркер на вопросе уже стоит, {@code from} сохраняется прежним: он говорит, к какому
     * репозиторию относится история ВЫШЕ, и от повторов её содержимое не меняется. Возврат туда же
     * маркер снимает — сменой относительно истории выше он больше не является.
     *
     * <p>Первое сообщение чата маркером не становится и здесь, но базовый штамп на нём переписать
     * обязано: истории выше нет, а репозиторий, на котором пойдёт прогон, стал другим. Оставь
     * прежний id — и блок активного проекта назовёт модели чужой репозиторий, то есть ровно ту
     * тихую ошибку со ссылками на файлы, ради которой он и печатается.
     */
    @Transactional
    public ChatMessageEntity markProjectSwitch(
            ChatMessageEntity question, ProjectSwitch projectSwitch) {
        final ChatMessageMeta meta = question.getMeta();
        if (question.getPosition() <= 1) {
            return chatMessageRepository.save(
                    question.withMeta(base(meta).withProjectSwitch(projectSwitch.to(), null)));
        }
        final String from =
                meta != null && meta.projectSwitchFrom() != null
                        ? meta.projectSwitchFrom()
                        : projectSwitch.from();
        final boolean switched = !from.equals(projectSwitch.to());
        return chatMessageRepository.save(
                question.withMeta(
                        base(meta)
                                .withProjectSwitch(
                                        switched ? projectSwitch.to() : null,
                                        switched ? from : null)));
    }

    /** Мета, поверх которой переписывается пометка проекта: у вопроса её могло не быть вовсе. */
    private static ChatMessageMeta base(@Nullable ChatMessageMeta meta) {
        return meta == null ? new ChatMessageMeta(null, false, List.of(), List.of()) : meta;
    }

    /**
     * Дописывает в конец чата всё, чего в нём ещё нет, — путь записи всей памяти (см. {@link
     * ChatHistoryMemory#add}). Ряды нумеруются от максимума позиции по чату, поэтому вызывающему не
     * нужно ни передавать историю целиком, ни знать, чем чат кончается.
     *
     * <p>Уже сохранённые сообщения ({@link IMessage}) отсеиваются: их приносит обратно сам advisor
     * памяти — он подставляет прочитанную историю в промпт и отдаёт на запись её последнее
     * user/tool-сообщение. Без фильтра предзаписанный вопрос (см. {@link #saveUserMessage})
     * задвоился бы вторым рядом.
     *
     * <p>Пустой текст — повод пропустить сообщение, но только если в нём нет и протокольных
     * tool-данных: у ASSISTANT-сегмента с одними tool_calls и у TOOL-ответа текста нет вовсе, а без
     * них пара {@code assistant.tool_calls} ↔ {@code tool.tool_call_id} не восстановится и
     * следующий запрос к модели упрётся в 400.
     */
    @Transactional
    public void append(String conversationId, List<Message> messages) {
        final AtomicLong position = new AtomicLong(lastPosition(conversationId));
        final List<ChatMessageEntity> newRows =
                messages.stream()
                        .filter(message -> !(message instanceof IMessage))
                        .map(message -> new Pending(message, toolDataOf(message)))
                        .filter(p -> Strings.isNotBlank(p.message().getText()) || p.hasToolData())
                        .map(
                                p ->
                                        new ChatMessageEntity(
                                                0,
                                                conversationId,
                                                p.message().getText() == null
                                                        ? ""
                                                        : p.message().getText(),
                                                p.message().getMessageType(),
                                                position.incrementAndGet(),
                                                false,
                                                false,
                                                LocalDateTime.now(),
                                                null,
                                                p.toolData()))
                        .toList();
        final List<ChatMessageEntity> saved = new ArrayList<>();
        chatMessageRepository.saveAll(newRows).forEach(saved::add);
        toolCalls.index(conversationId, saved);
        toolCallEvents.publish(conversationId, saved);
    }

    /**
     * Записывает git-команду, которую пользователь выполнил из этого чата, отдельным рядом истории.
     *
     * <p>Ряд, а не поле на следующем вопросе: команду выполняют между сообщениями, и её результат
     * надо показать сразу — вопроса, к которому его можно было бы приложить, может не быть ещё
     * долго. Тип {@code USER}, потому что это и правда ход пользователя, а не слова ассистента;
     * контент пустой, весь смысл — в мете (см. {@link GitEventMeta}), и текст для модели собирается
     * на чтении в {@link #promptRow}, как и маркер смены проекта.
     *
     * <p>Ряд пишется в любом месте истории, первым в том числе: команда, выполненная в ещё пустом
     * чате, — такое же его начало, как вложение, загруженное до первого вопроса.
     *
     * <p>Оборванный хвост чинится ПЕРЕД записью — по той же причине, по которой это делает {@code
     * ChatRunService.start} перед сохранением вопроса: {@link #repairDanglingToolCalls} смотрит
     * только на последний ряд, и {@code USER}, вставший поверх незакрытой пары {@code
     * assistant.tool_calls} ↔ {@code tool}, спрятал бы её навсегда. Чат после этого получал бы
     * {@code 400} от модели на каждое следующее сообщение.
     */
    @Transactional
    public ChatMessageEntity appendGitEvent(String conversationId, GitEventMeta event) {
        repairDanglingToolCalls(conversationId);
        return chatMessageRepository.save(
                new ChatMessageEntity(
                        0,
                        conversationId,
                        "",
                        MessageType.USER,
                        lastPosition(conversationId) + 1,
                        false,
                        false,
                        LocalDateTime.now(),
                        ChatMessageMeta.ofGitEvent(event)));
    }

    /**
     * Записывает откат файловых правок ответа отдельным рядом истории — тем же приёмом и по тем же
     * причинам, что и {@link #appendGitEvent}: действие происходит между сообщениями, показать его
     * надо сразу, а текст для модели собирается на чтении ({@link PromptNotices#fileRevertNotice}).
     *
     * <p>Хвост чинится перед записью по той же причине: {@link #repairDanglingToolCalls} смотрит
     * только на последний ряд, и {@code USER}, вставший поверх незакрытой пары, спрятал бы её
     * навсегда. Здесь это не теория — откат как раз и делают сразу после ответа, в том числе
     * остановленного посреди работы инструментов.
     */
    @Transactional
    public ChatMessageEntity appendFileRevert(String conversationId, FileRevertMeta revert) {
        repairDanglingToolCalls(conversationId);
        return chatMessageRepository.save(
                new ChatMessageEntity(
                        0,
                        conversationId,
                        "",
                        MessageType.USER,
                        lastPosition(conversationId) + 1,
                        false,
                        false,
                        LocalDateTime.now(),
                        ChatMessageMeta.ofFileRevert(revert)));
    }

    /**
     * Репозиторий, на котором чат работал последним, — по штампам живой истории: маркеру смены
     * проекта или базовому штампу первого сообщения (см. {@link ChatMessageMeta}). {@code null} —
     * штампов нет вовсе, то есть чат начат версией без них.
     *
     * <p>Нужен откату файловых правок: селектор проекта в интерфейсе можно переключить сразу после
     * ответа, и «репозиторий, выбранный сейчас» — не тот, в котором ответ правил файлы. Штампы
     * стоят на вопросах, а после последнего ответа вопросов уже нет, поэтому поиск с конца находит
     * именно тот проект, в котором ответ и работал.
     */
    public @Nullable String lastStampedProject(String conversationId) {
        final List<ChatMessageEntity> rows =
                chatMessageRepository
                        .findChatMessageByConversationIdAndSummarizedFalseOrderByCreatedAtAscPositionAsc(
                                conversationId);
        for (ChatMessageEntity row : rows.reversed()) {
            if (row.getMeta() != null && row.getMeta().project() != null) {
                return row.getMeta().project();
            }
        }
        return null;
    }

    /**
     * Ряды последнего ответа чата — хвост живого окна после последнего вопроса (см. {@link
     * #tailAfterLastUser}). Нужен откату файловых правок: что именно откатывать, он вычитывает из
     * {@code tool_data} этих рядов.
     *
     * <p>Живое окно, а не вся история: откатывают только что увиденное, а сжатая часть — это уже
     * другой разговор, у неё и {@code tool_data} может не быть.
     */
    public List<ChatMessageEntity> lastAnswerRows(String conversationId) {
        return tailAfterLastUser(
                chatMessageRepository
                        .findChatMessageByConversationIdAndSummarizedFalseOrderByCreatedAtAscPositionAsc(
                                conversationId));
    }

    /**
     * Записывает доставленное из очереди сообщение (см. {@code PendingMessageService}) — вопрос,
     * отправленный во время активного прогона и доехавший до истории в безопасном «окне».
     *
     * <p>{@code interjection} — доставка случилась ПОСРЕДИ прогона, между итерациями tool-цикла:
     * ряд получает флаг в мете, и {@link #promptRow} обернёт его текст нотисом {@link
     * #interjectionNotice}. {@code false} — прогон уже завершился, и сообщение становится обычным
     * вопросом: следующим ходом чата, который подберёт follow-up прогон или «Повторить».
     *
     * <p>За протокольную валидность точки вставки отвечает вызывающий: либо хвост истории — TOOL
     * (окно advisor'а, где ответы инструментов уже записаны), либо {@link #repairDanglingToolCalls}
     * уже отработал (терминальная доставка и восстановление). Сам метод хвост не чинит: в окне
     * advisor'а ремонт увидел бы здоровую пару и был бы пустой тратой, а прятать вызов ремонта в
     * два из трёх путей — значит молча положиться на третий.
     */
    @Transactional
    public ChatMessageEntity saveDeliveredPending(
            String conversationId,
            String text,
            List<ContextItem> contextItems,
            boolean interjection) {
        final ChatMessageMeta meta =
                interjection
                        ? ChatMessageMeta.ofInterjection(contextItems)
                        : ChatMessageMeta.ofUserMessage(contextItems, null, null);
        return chatMessageRepository.save(
                new ChatMessageEntity(
                        0,
                        conversationId,
                        text,
                        MessageType.USER,
                        lastPosition(conversationId) + 1,
                        false,
                        false,
                        LocalDateTime.now(),
                        meta));
    }

    /**
     * Чинит оборванную пару tool-сообщений в хвосте диалога. Если прогон прервали (stop, ошибка,
     * падение процесса) во время выполнения инструментов, последняя строка — ASSISTANT с tool_calls
     * без парной TOOL-строки; следующий запрос к модели с таким хвостом получил бы 400
     * (assistant.tool_calls без tool-ответов). Достраиваем синтетический TOOL-ответ.
     *
     * <p>Оборванной может быть только последняя пара: цикл строго чередует assistant(tool_calls) →
     * tool, и всё, что раньше хвоста, уже сохранено парами.
     */
    @Transactional
    public void repairDanglingToolCalls(String conversationId) {
        chatMessageRepository
                .findFirstByConversationIdOrderByPositionDesc(conversationId)
                .filter(last -> last.getType() == MessageType.ASSISTANT)
                .filter(
                        last ->
                                last.getToolData() != null
                                        && last.getToolData().toolCalls() != null
                                        && !last.getToolData().toolCalls().isEmpty())
                .ifPresent(
                        last -> {
                            final List<ToolData.Call> calls =
                                    Objects.requireNonNull(
                                            Objects.requireNonNull(last.getToolData()).toolCalls());
                            final List<ToolData.Response> responses =
                                    calls.stream()
                                            .map(
                                                    c ->
                                                            new ToolData.Response(
                                                                    c.id(),
                                                                    c.name(),
                                                                    "[interrupted — no result]"))
                                            .toList();
                            log.info(
                                    "Repairing dangling tool_calls tail for {} ({} synthetic responses)",
                                    conversationId,
                                    responses.size());
                            final ChatMessageEntity repaired =
                                    chatMessageRepository.save(
                                            new ChatMessageEntity(
                                                    0L,
                                                    conversationId,
                                                    "",
                                                    MessageType.TOOL,
                                                    last.getPosition() + 1,
                                                    false,
                                                    false,
                                                    LocalDateTime.now(),
                                                    null,
                                                    new ToolData(null, responses)));
                            // Ремонтная строка идёт мимо append, но в индекс попасть обязана:
                            // без её responseMessageId у оборванного вызова навсегда остаётся
                            // «ответа ещё нет», то есть модалка деталей показывает работающим
                            // инструмент, который уже никогда не ответит.
                            toolCalls.index(conversationId, List.of(repaired));
                        });
    }

    /**
     * Записывает всё, что известно о прогоне только по его завершении, на ASSISTANT-ряды, которые
     * advisor-цепочка сохранила по ходу (см. {@link #append}): плашки вызовов инструментов, модель
     * и токены. На записи не известно ничего из этого — {@link ChatHistoryMemory#add} получает от
     * advisor'а только сообщения, модель прогона живёт в его настройках, токены — в {@code
     * RunScope}, а вызовы — в снимке коллектора.
     *
     * <p>Одной операцией, а не тремя: плашки ищут необогащённые сегменты по {@code meta == null},
     * поэтому порознь это была бы пара с негласным порядком — модель, проставленная первой, прятала
     * бы от плашек ряды этого же прогона, и вызовы инструментов не показались бы никогда. Заодно
     * это одно чтение хвоста и одна запись вместо двух.
     *
     * <p>Модель проставляется каждому ряду прогона, токены — только последнему: числа относятся к
     * прогону целиком, и копия на каждом сегменте заставила бы читающего выбирать между одинаковыми
     * (см. {@link ChatMessageMeta}). Не измеренный прогон не помечается вовсе — {@code null} в мете
     * значит «не измерено», и уверенный ноль был бы неправдой.
     *
     * <p>Ряды прогона — это хвост после последнего вопроса (см. {@link #tailAfterLastUser}). Ряды
     * оборванных прогонов, оставшиеся в том же хвосте, уже помечены своей моделью и второй раз не
     * переписываются: у ответа стоит та модель, что его написала, а не та, на которой сдались.
     *
     * @param toolCalls снимок вызовов прогона в хронологическом порядке; пустой — инструментов
     *     прогон не звал
     * @return плашки, которые эта запись проставила, в хронологическом порядке — их же прогон шлёт
     *     финальным live-событием {@code TOOL_CALLS}, чтобы не считать то же самое дважды
     */
    @Transactional
    public List<ToolInvocationMeta> markRunResult(
            String conversationId,
            String runId,
            String model,
            RunTokenUsage usage,
            List<ToolInvocation> toolCalls) {
        final List<ChatMessageEntity> answers =
                tailAfterLastUser(
                                chatMessageRepository
                                        .findChatMessageByConversationIdAndSummarizedFalseOrderByCreatedAtAscPositionAsc(
                                                conversationId))
                        .stream()
                        .filter(row -> row.getType() == MessageType.ASSISTANT)
                        // meta == null в хвосте может быть только у рядов текущего прогона (хвост
                        // на старте прогона пуст — см. unansweredUserMessage). Ряды с метой
                        // трогаем только свои: withRun переписал бы чужому ряду и runId.
                        .filter(
                                row ->
                                        row.getMeta() == null
                                                || (row.getMeta().model() == null
                                                        && runId.equals(row.getMeta().runId())))
                        .toList();
        if (answers.isEmpty()) {
            return List.of();
        }
        final Map<Long, List<ToolInvocationMeta>> invocations =
                ToolCallService.runInvocations(
                        answers.stream().filter(row -> row.getMeta() == null).toList(), toolCalls);
        final List<ChatMessageEntity> updated = new ArrayList<>(answers.size());
        for (ChatMessageEntity answer : answers) {
            // Мету пишем поверх существующей, а плашки достаются только рядам без неё — их и
            // отбирает фильтр в runInvocations выше. Ослабишь его — собирай мету поверх
            // существующей и здесь, иначе она потеряется.
            final ChatMessageMeta existing = answer.getMeta();
            final List<ToolInvocationMeta> metas = invocations.get(answer.getId());
            final ChatMessageMeta base =
                    existing != null
                            ? existing
                            : new ChatMessageMeta(null, false, metas == null ? List.of() : metas);
            updated.add(answer.withMeta(base.withRun(runId, model)));
        }
        if (!usage.isEmpty()) {
            final int last = updated.size() - 1;
            final ChatMessageEntity answer = updated.get(last);
            // Мета здесь заведомо есть — её только что проставил withRun выше.
            updated.set(
                    last,
                    answer.withMeta(Objects.requireNonNull(answer.getMeta()).withUsage(usage)));
        }
        chatMessageRepository.saveAll(updated);
        return invocations.values().stream().flatMap(List::stream).toList();
    }

    /**
     * Ряды текущего хода — всё, что записано после последнего вопроса пользователя. Прогоны на чат
     * строго последовательны, поэтому «после последнего вопроса» и есть «этим ходом»; повтор
     * упавшего прогона нового вопроса не заводит, так что в хвост попадают и его ряды тоже.
     *
     * <p>Статический и общий на всех, кто ищет границу хода ({@link #markRunResult}, {@code
     * SummarizeWindow}): вторая копия этого правила разошлась бы с первой молча.
     */
    static List<ChatMessageEntity> tailAfterLastUser(List<ChatMessageEntity> rows) {
        int lastUser = -1;
        for (int i = 0; i < rows.size(); i++) {
            if (opensATurn(rows.get(i))) {
                lastUser = i;
            }
        }
        return rows.subList(lastUser + 1, rows.size());
    }

    /**
     * Открывает ли ряд новый ход разговора. Не всякий {@code USER}-ряд им открывается, и всё, что
     * ищет «последний вопрос», обязано смотреть сквозь два исключения:
     *
     * <ul>
     *   <li>ряд события — git-команды или отката файловых правок — ничего не спрашивает и ответа не
     *       ждёт;
     *   <li>вопрос, доставленный посреди прогона ({@code meta.interjection}), задан внутри уже
     *       идущего хода — ход открыл вопрос выше него.
     * </ul>
     *
     * <p>Оба попадают в историю в произвольный момент работы модели, и оба, посчитанные границей
     * хода, обрезали бы хвост прогона посередине: ряды выше остались бы без модели и без плашек
     * вызовов навсегда (см. {@link #markRunResult}), а окно сжатия открылось бы на ответе к
     * вопросу, которого в нём уже нет ({@code SummarizeWindow}).
     */
    static boolean opensATurn(ChatMessageEntity row) {
        return row.getType() == MessageType.USER
                && (row.getMeta() == null || (!isEventRow(row) && !row.getMeta().interjection()));
    }

    /**
     * Ряд, который оставило в истории действие пользователя, а не его реплика: git-команда или
     * откат файловых правок ответа. Текста у такого ряда нет — его собирает {@link PromptNotices}
     * при чтении, — и ходом разговора он не является.
     */
    static boolean isEventRow(ChatMessageEntity row) {
        return row.getMeta() != null
                && (row.getMeta().gitEvent() != null || row.getMeta().fileRevert() != null);
    }

    public void delete(String conversationId) {
        chatMessageRepository.deleteChatMessageByConversationId(conversationId);
    }

    private long lastPosition(String conversationId) {
        // Именно max-проекция: выборка последнего ряда целиком тащила бы его content и
        // tool_data (у TOOL-ответа это весь результат инструмента) дважды на каждую
        // итерацию tool-цикла — ради одного long.
        return chatMessageRepository.maxPosition(conversationId);
    }

    /** Протокольные tool-данные сообщения, если они есть (иначе {@code null}). */
    private static @Nullable ToolData toolDataOf(Message message) {
        if (message instanceof AssistantMessage assistantMessage
                && assistantMessage.hasToolCalls()) {
            return sanitizeToolCallArguments(ToolData.from(assistantMessage));
        }
        if (message instanceof ToolResponseMessage toolResponseMessage
                && !toolResponseMessage.getResponses().isEmpty()) {
            return ToolData.from(toolResponseMessage);
        }
        return null;
    }

    /**
     * Гарантирует, что {@code tool_data.toolCalls[].arguments} перед сохранением — валидный JSON
     * (см. {@link RecordingToolCallback#sanitizeArguments}). Единственная точка входа протокольных
     * tool_calls в БД ({@link #toolDataOf}), поэтому дальше по коду (в т.ч. при воспроизведении
     * истории модели) малформенный JSON от модели уже не встретится.
     */
    private static ToolData sanitizeToolCallArguments(ToolData toolData) {
        if (toolData.toolCalls() == null) {
            return toolData;
        }
        return new ToolData(
                toolData.toolCalls().stream()
                        .map(
                                call ->
                                        new ToolData.Call(
                                                call.id(),
                                                call.type(),
                                                call.name(),
                                                RecordingToolCallback.sanitizeArguments(
                                                        call.arguments())))
                        .toList(),
                null);
    }

    // ── Чтение ────────────────────────────────────────────────────────────────

    /**
     * Ряд истории в том виде, в каком его увидит модель: сама строка и её текст в промпте.
     *
     * <p>Текст — не то же самое, что {@code chat_message.content}: к вопросу с приложенным
     * контекстом дописывается блок описи (см. {@link ContextItemService#renderAll}), который
     * собирается при каждом чтении и в БД не попадает — иначе удалённое вложение осталось бы в
     * истории вечным обещанием файла, которого больше нет.
     *
     * <p>Пара нужна затем, что вес истории считают не только по её тексту: {@code SummarizeService}
     * решает по нему, что сжимать, а метить сжатое умеет только по позициям из {@code entity}.
     */
    public record PromptRow(ChatMessageEntity entity, String text) {

        /**
         * Промпт строится по тому же {@link #text}, по которому считается вес окна: второго способа
         * узнать текст сообщения здесь нет. Обёртка нужна ровно тогда, когда текст разошёлся с
         * сохранённой строкой, — а разойтись он может только у вопроса (см. {@code promptRow}).
         */
        public Message toMessage() {
            return text.equals(entity.getContent())
                    ? entity.getMessage()
                    : new UserChatMessage(entity, text);
        }
    }

    /** Окно истории для модели — то, что подставит в промпт advisor памяти. */
    public List<Message> promptMessages(String conversationId) {
        return promptRows(conversationId).stream().map(PromptRow::toMessage).toList();
    }

    /**
     * Живая (несжатая) история вместе с текстом, который реально уедет модели. Единственный
     * источник правды об этом тексте: и промпт, и оценка веса окна в {@code SummarizeService}
     * строятся отсюда, поэтому разойтись в том, «что именно видит модель», они не могут. Ровно один
     * запрос за описями на всё окно.
     *
     * <p>Строки-сводки ({@code summary = true}) остаются в выборке: они тоже уезжают модели в
     * каждом запросе, значит тоже занимают бюджет.
     */
    public List<PromptRow> promptRows(String conversationId) {
        return promptRowsFor(
                conversationId,
                chatMessageRepository
                        .findChatMessageByConversationIdAndSummarizedFalseOrderByCreatedAtAscPositionAsc(
                                conversationId));
    }

    /**
     * Живое окно ДО указанной позиции — то же, что {@link #promptRows}, но без неё самой и всего,
     * что за ней. Нужно сжатию ({@code CompactService}): команда {@code /compact} к моменту раунда
     * уже сохранена, а материалом сжатия не является.
     *
     * <p>Отсечь хвост здесь, а не у вызывающего, обязательно из-за блока активного проекта: он
     * достаётся ряду, открывшему последний ход ({@link ActiveProjectNotice#anchor}), а команда —
     * обычный USER-ряд и ход открывает. Отфильтруй вызывающий готовые {@link PromptRow}, и блок
     * уехал бы вместе с командой: модель не узнала бы, в каком репозитории написано то, что она
     * сжимает, а окно разошлось бы с последним запросом чата на последнем же вопросе — то есть
     * ровно там, где провайдер перестал бы засчитывать кэш промпта.
     */
    public List<PromptRow> promptRowsBefore(String conversationId, long position) {
        return promptRowsFor(
                conversationId,
                chatMessageRepository
                        .findChatMessageByConversationIdAndSummarizedFalseOrderByCreatedAtAscPositionAsc(
                                conversationId)
                        .stream()
                        .filter(row -> row.getPosition() < position)
                        .toList());
    }

    /**
     * Промпт-вид КОНКРЕТНЫХ рядов — для сообщений, которые доставлены внутрь уже идущего прогона и
     * должны доехать до модели этой же итерацией (см. {@code InterjectionAdvisor}): окно итерации
     * собрано advisor-ом памяти раньше, чем эти ряды записаны, и перечитывать его целиком ради
     * хвоста незачем. Текст — тем же {@link #promptRow}, что и у полного окна: нотисы и опись
     * вложений обязаны быть теми же, какими их увидит следующая итерация из {@link #promptRows}.
     */
    public List<Message> promptMessagesFor(String conversationId, List<ChatMessageEntity> rows) {
        return promptRowsFor(conversationId, rows).stream().map(PromptRow::toMessage).toList();
    }

    private List<PromptRow> promptRowsFor(String conversationId, List<ChatMessageEntity> rows) {
        final Map<Long, String> context = contextItemService.renderAll(conversationId, rows);
        // Блок активного проекта собирается один раз на окно и достаётся одному ряду — см.
        // ActiveProjectNotice. Пустой якорь значит «ставить некуда», и тогда его не собирают вовсе.
        final long anchor = ActiveProjectNotice.anchor(rows);
        final String project = anchor < 0 ? "" : activeProject.render(conversationId, rows);
        return rows.stream()
                .map(
                        entity ->
                                promptRow(
                                        entity,
                                        context.get(entity.getId()),
                                        entity.getPosition() == anchor ? project : ""))
                .toList();
    }

    /**
     * Единственное место, где решается, обрастает ли строка описью: от него зависят обе стороны
     * сразу — и промпт, и оценка веса окна. Проверь тип во второй раз ниже по течению, и стороны
     * разойдутся, а расхождение будет тихим.
     *
     * <p>Опись получает только вопрос: блок говорит о том, что приложил пользователь, и на ответе
     * модели был бы просто неправдой. Остальные отдают {@code content} как есть — этот путь
     * проходят все строки окна на каждой итерации tool-цикла, и склейка с пустой строкой заводила
     * бы новую копию текста на каждую.
     *
     * <p>Вопрос, которым чат сменил проект, дополнительно получает предупреждение ПЕРЕД текстом
     * (см. {@link PromptNotices}): всё выше в истории читано в прежнем репозитории, и без этой
     * строки модель сочтёт те пути и содержимое актуальными. Как и опись, предупреждение собирается
     * при чтении и в БД не попадает.
     *
     * @param activeProject блок активного проекта ({@link ActiveProjectNotice}) — только у ряда, на
     *     который он назначен, у остальных пустая строка. Идёт последним, ПОСЛЕ описи: это
     *     действующая справка о том, где искать файлы, и стоять ей у самого места ответа
     */
    private static PromptRow promptRow(
            ChatMessageEntity entity, @Nullable String inventory, String activeProject) {
        if (entity.getType() != MessageType.USER) {
            return new PromptRow(entity, entity.getContent());
        }
        final String eventNotice = PromptNotices.eventNotice(entity.getMeta());
        if (!eventNotice.isEmpty()) {
            // Ряд события (git-команда, откат правок) несёт только нотис: описи у него нет
            // (пользователь ничего не прикладывал), маркера смены проекта — тем более, чат таким
            // рядом никуда не переходит, и блока активного проекта — ход событием не открывается
            // (см. ActiveProjectNotice#anchor).
            return new PromptRow(entity, eventNotice);
        }
        final String notice =
                PromptNotices.projectSwitchNotice(entity.getMeta())
                        + PromptNotices.interjectionNotice(entity.getMeta());
        if (notice.isEmpty() && inventory == null && activeProject.isEmpty()) {
            return new PromptRow(entity, entity.getContent());
        }
        return new PromptRow(
                entity,
                notice
                        + entity.getContent()
                        + (inventory == null ? "" : inventory)
                        + (activeProject.isEmpty() ? "" : "\n\n" + activeProject));
    }

    /**
     * Последнее сообщение чата — но только если это вопрос пользователя, на который модель ещё
     * ничего не ответила. Единственное состояние, из которого «Повторить» означает продолжить тот
     * же ход: дописывать в историю нечего, прогон просто запускается заново поверх неё (см. {@code
     * ChatRunService.start} в режиме повтора).
     *
     * <p>Как только в хвосте появился ASSISTANT или TOOL — пусть даже оборванный сегмент упавшего
     * прогона, — повтор запрещён. Молча «переиграть» ход модели можно было бы только одним из двух
     * способов: задвоив вопрос вторым USER-рядом или удалив то, что модель успела сделать (включая
     * побочные эффекты уже выполненных инструментов). Оба варианта хуже прямого продолжения
     * диалога, поэтому дальше пользователь пишет сам.
     *
     * <p>Ряды git-команд в хвосте пропускаются, а не запрещают повтор. Они тоже {@code USER}, но
     * вопросом не являются, и вопрос, оставшийся без ответа, не перестаёт им быть оттого, что
     * человек успел сделать pull, пока думал. Повторный прогон прочитает их наравне с остальной
     * историей и ответит на вопрос один раз — зная, что репозиторий с тех пор сдвинулся.
     *
     * <p>Хвост читается пачкой в двадцать рядов, а не по одному: команд подряд может быть
     * несколько. Двадцать — это граница, а не доказательство: набрать их столько, не написав ни
     * слова, можно, и тогда «Повторить» пропадёт у вопроса, который его заслуживает. Цена ошибки в
     * эту сторону — одна кнопка, а вопрос можно задать заново; в другую — прогон поверх чужого
     * хвоста.
     */
    public Optional<ChatMessageEntity> unansweredUserMessage(String conversationId) {
        for (ChatMessageEntity row :
                chatMessageRepository.findTop20ByConversationIdOrderByPositionDesc(
                        conversationId)) {
            if (isEventRow(row)) {
                continue;
            }
            return row.getType() == MessageType.USER ? Optional.of(row) : Optional.empty();
        }
        return Optional.empty();
    }

    /** Вся история чата для показа целиком, без строк-сводок. */
    public List<ChatMessageEntity> displayMessages(String conversationId) {
        return chatMessageRepository
                .findChatMessageByConversationIdAndSummaryFalseOrderByCreatedAtAscPositionAsc(
                        conversationId);
    }

    public Page findLatestPage(String conversationId, int limit) {
        return toPage(chatMessageRepository.findLatest(conversationId, limit + 1), limit);
    }

    public Page findPageBefore(
            String conversationId, LocalDateTime beforeCreatedAt, long beforeId, int limit) {
        return toPage(
                chatMessageRepository.findBefore(
                        conversationId, beforeCreatedAt, beforeId, limit + 1),
                limit);
    }

    private Page toPage(List<ChatMessageEntity> rowsDesc, int limit) {
        boolean hasMore = rowsDesc.size() > limit;
        List<ChatMessageEntity> chrono =
                (hasMore ? rowsDesc.subList(0, limit) : rowsDesc).reversed();
        MessageCursor cursor =
                chrono.isEmpty()
                        ? null
                        : new MessageCursor(
                                chrono.getFirst().getCreatedAt(), chrono.getFirst().getId());
        return new Page(chrono, hasMore, cursor);
    }

    public record Page(
            List<ChatMessageEntity> messages,
            boolean hasMore,
            @Nullable MessageCursor oldestCursor) {}
}
