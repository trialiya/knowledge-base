package io.github.trialiya.kb.service.chat.memory;

import io.github.trialiya.kb.model.chat.dto.MessageCursor;
import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import io.github.trialiya.kb.model.chat.entity.ChatMessageMeta;
import io.github.trialiya.kb.model.chat.entity.ContextItem;
import io.github.trialiya.kb.model.chat.entity.GitEventMeta;
import io.github.trialiya.kb.model.chat.spring.IMessage;
import io.github.trialiya.kb.model.chat.spring.UserChatMessage;
import io.github.trialiya.kb.model.project.ProjectSwitch;
import io.github.trialiya.kb.model.tool.ToolData;
import io.github.trialiya.kb.repository.ChatMessageRepository;
import io.github.trialiya.kb.service.chat.context.ContextItemService;
import io.github.trialiya.kb.tools.RecordingToolCallback;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
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
     * meta} и делает историю выше честной: и промпт (см. {@link #promptRow}), и фронт предупредят,
     * что прочитанное раньше относится к прежнему репозиторию. Первому сообщению чата маркер не
     * ставится: над пустой историей предупреждать не о чем, даже когда проект в нём назван явно.
     */
    @Transactional
    public ChatMessageEntity saveUserMessage(
            String conversationId,
            String text,
            List<ContextItem> contextItems,
            @Nullable ProjectSwitch projectSwitch) {
        final long position = lastPosition(conversationId) + 1;
        final ProjectSwitch marked = position > 1 ? projectSwitch : null;
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
                                contextItems,
                                marked == null ? null : marked.to(),
                                marked == null ? null : marked.from())));
    }

    /**
     * Тот же маркер, но на уже записанном вопросе — путь повтора прогона: нового сообщения не
     * появляется, а проект прогона сменился, и предупредить о прежнем репозитории всё равно надо.
     *
     * <p>Если маркер на вопросе уже стоит, {@code from} сохраняется прежним: он говорит, к какому
     * репозиторию относится история ВЫШЕ, и от повторов её содержимое не меняется. Возврат туда же
     * маркер снимает — сменой относительно истории выше он больше не является.
     */
    @Transactional
    public ChatMessageEntity markProjectSwitch(
            ChatMessageEntity question, ProjectSwitch projectSwitch) {
        final ChatMessageMeta meta = question.getMeta();
        if (question.getPosition() <= 1) {
            return question;
        }
        final String from =
                meta != null && meta.projectSwitchFrom() != null
                        ? meta.projectSwitchFrom()
                        : projectSwitch.from();
        final boolean switched = !from.equals(projectSwitch.to());
        final ChatMessageMeta base =
                meta == null ? new ChatMessageMeta(null, false, List.of(), List.of()) : meta;
        return chatMessageRepository.save(
                question.withMeta(
                        base.withProjectSwitch(
                                switched ? projectSwitch.to() : null, switched ? from : null)));
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
     * Проставляет прогон и его модель на ASSISTANT-рядах, которые advisor-цепочка записала по ходу
     * прогона (см. {@link #append}) — модель на записи неизвестна: {@link ChatHistoryMemory#add}
     * получает от advisor'а только сообщения, а id модели живёт в настройках прогона.
     *
     * <p>Идёт ПОСЛЕ {@code ToolCallService.attachRunMeta}: та ищет необогащённые сегменты по {@code
     * meta == null}, и проставленная раньше времени модель спрятала бы от неё вызовы инструментов.
     * Обратный порядок безопасен для самой модели — {@link ChatMessageMeta#withRun} дописывает поле
     * к уже сохранённой мете, — но плашки вызовов после него не появятся.
     *
     * <p>Ряды прогона — это хвост после последнего вопроса (см. {@link #tailAfterLastUser}). Ряды
     * оборванных прогонов, оставшиеся в том же хвосте, уже помечены своей моделью и второй раз не
     * переписываются: у ответа стоит та модель, что его написала, а не та, на которой сдались.
     */
    @Transactional
    public void markRunModel(String conversationId, String runId, String model) {
        final List<ChatMessageEntity> updated =
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
                        .map(
                                row ->
                                        row.withMeta(
                                                (row.getMeta() == null
                                                                ? new ChatMessageMeta(
                                                                        null, false, List.of())
                                                                : row.getMeta())
                                                        .withRun(runId, model)))
                        .toList();
        if (!updated.isEmpty()) {
            chatMessageRepository.saveAll(updated);
        }
    }

    /**
     * Ряды текущего хода — всё, что записано после последнего вопроса пользователя. Прогоны на чат
     * строго последовательны, поэтому «после последнего вопроса» и есть «этим ходом»; повтор
     * упавшего прогона нового вопроса не заводит, так что в хвост попадают и его ряды тоже.
     *
     * <p>Статический и общий на два вызывающих ({@link #markRunModel} и {@code
     * ToolCallService#attachRunMeta}): оба дописывают мету рядам одного и того же хода, и вторая
     * копия этого правила разошлась бы с первой молча. Статический — потому что бином эти два
     * сервиса связать нельзя: {@link ChatHistoryService} уже зависит от {@code ToolCallService}.
     */
    static List<ChatMessageEntity> tailAfterLastUser(List<ChatMessageEntity> rows) {
        int lastUser = -1;
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).getType() == MessageType.USER && !isGitEvent(rows.get(i))) {
                lastUser = i;
            }
        }
        return rows.subList(lastUser + 1, rows.size());
    }

    /**
     * Ряд git-команды — тоже {@code USER}, но ходом пользователя в разговоре не является: он ничего
     * не спрашивает и ответа не ждёт. Всё, что ищет «последний вопрос», обязано смотреть сквозь
     * него, иначе команда, выполненная посреди прогона, обрежет его хвост — и ряды выше останутся
     * без модели и без плашек вызовов навсегда.
     */
    private static boolean isGitEvent(ChatMessageEntity row) {
        return row.getMeta() != null && row.getMeta().gitEvent() != null;
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
        final List<ChatMessageEntity> rows =
                chatMessageRepository
                        .findChatMessageByConversationIdAndSummarizedFalseOrderByCreatedAtAscPositionAsc(
                                conversationId);
        final Map<Long, String> context = contextItemService.renderAll(conversationId, rows);
        return rows.stream().map(entity -> promptRow(entity, context.get(entity.getId()))).toList();
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
     * (см. {@link #projectSwitchNotice}): всё выше в истории читано в прежнем репозитории, и без
     * этой строки модель сочтёт те пути и содержимое актуальными. Как и опись, предупреждение
     * собирается при чтении и в БД не попадает.
     */
    private static PromptRow promptRow(ChatMessageEntity entity, @Nullable String inventory) {
        if (entity.getType() != MessageType.USER) {
            return new PromptRow(entity, entity.getContent());
        }
        final String gitCommand = gitCommandNotice(entity.getMeta());
        if (!gitCommand.isEmpty()) {
            // Ряд команды несёт только её: описи у него нет (пользователь ничего не прикладывал),
            // а маркера смены проекта — тем более, чат этим рядом никуда не переходит.
            return new PromptRow(entity, gitCommand);
        }
        final String notice = projectSwitchNotice(entity.getMeta());
        if (notice.isEmpty() && inventory == null) {
            return new PromptRow(entity, entity.getContent());
        }
        return new PromptRow(
                entity, notice + entity.getContent() + (inventory == null ? "" : inventory));
    }

    /**
     * Текст маркера смены проекта для модели. Требование «сохраняй дословно» адресовано
     * summarizer'у: его вход строится из этих же {@code PromptRow}, и потерянный при сжатии маркер
     * снова сделал бы раннюю историю «актуальной» (правило продублировано в {@code summarizer.md}).
     */
    private static String projectSwitchNotice(@Nullable ChatMessageMeta meta) {
        if (meta == null || meta.projectSwitchFrom() == null) {
            return "";
        }
        return "<project-switched from=\""
                + meta.projectSwitchFrom()
                + "\" to=\""
                + meta.project()
                + "\">\n"
                + "The user switched this chat to another project at this message. Everything"
                + " earlier in the conversation — file paths, file contents, grep and script"
                + " results — belongs to project \""
                + meta.projectSwitchFrom()
                + "\"; do not assume any of it exists or looks the same in \""
                + meta.project()
                + "\". Re-read whatever you need with the tools. When summarizing, preserve this"
                + " notice verbatim.\n"
                + "</project-switched>\n\n";
    }

    /**
     * Текст ряда git-команды для модели. Вывод самого git сюда не идёт: модели нужно знать, что
     * репозиторий сдвинулся и куда, а не читать «Fast-forward» построчно — вывод для человека и
     * лежит там, где человек его открывает.
     *
     * <p>Отказ рассказывается наравне с успехом, и он важнее: после отклонённого push ветка
     * осталась там же, где была, и модель, решившая иначе, будет строить работу на несуществующем
     * состоянии. Требование «сохраняй дословно», как и у маркера смены проекта, адресовано
     * summarizer'у (правило продублировано в {@code prompt/summarizer.md}).
     */
    public static String gitCommandNotice(@Nullable ChatMessageMeta meta) {
        if (meta == null || meta.gitEvent() == null) {
            return "";
        }
        final GitEventMeta event = meta.gitEvent();
        return "<git-command command=\""
                + attr(event.command())
                + "\" outcome=\""
                + (event.ok() ? "ok" : "refused")
                + "\""
                + (event.project() == null ? "" : " project=\"" + attr(event.project()) + "\"")
                + (event.branch() == null ? "" : " branch=\"" + attr(event.branch()) + "\"")
                + ">\n"
                + "The user ran this git command on the project from this chat — not you, and not"
                + " through any tool of yours. "
                + (event.ok()
                        ? "It succeeded: the working tree may differ from what you read earlier, so"
                                + " re-read with the tools anything you are about to rely on."
                        : "It was refused, so the repository is where it was before — do not treat"
                                + " the command as done.")
                + " When summarizing, preserve this notice verbatim.\n"
                + "</git-command>\n";
    }

    /**
     * Значение атрибута нотиса. Имена веток и пути — единственное место, где текст извне попадает в
     * разметку, которую читает модель, а git запрещает в них далеко не всё: ни кавычка, ни угловые
     * скобки под запрет не попадают. Кавычкой закрывают атрибут, угловой скобкой — сам тег, и
     * ветка, названная {@code main>...</git-command}, дописала бы модели произвольный текст поверх
     * нотиса. Вывод команды такой поверхностью не является: он модели не показывается вовсе.
     */
    private static String attr(String value) {
        return value.replace("\"", "'").replace("<", "‹").replace(">", "›");
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
            if (isGitEvent(row)) {
                continue;
            }
            return row.getType() == MessageType.USER ? Optional.of(row) : Optional.empty();
        }
        return Optional.empty();
    }

    /**
     * Репозитории, на которых этот чат уже работал, в порядке появления, — то, что промпт называет
     * модели как «выбирались раньше» ({@code ProjectPromptService}). Читающие инструменты берут
     * проект аргументом, и без этого списка модель не знает ни одного id, кроме активного: спросить
     * про репозиторий, из которого половина истории и прочитана, ей было бы нечем.
     *
     * <p>Источник — маркеры смены проекта на вопросах ({@code ChatMessageMeta}): чат хранит только
     * текущий проект ({@code chat_topic.project}), а куда он ходил до того, знают лишь они. Обе
     * стороны маркера идут в список: {@code from} — репозиторий, в котором читана история выше,
     * {@code to} — тот, на который перешли (он же обычно активный, и его вызывающий отсеивает сам).
     *
     * <p>Активный проект сюда не подмешивается: этот метод отвечает на «где чат уже был», а «где он
     * сейчас» знает вызывающий, и знает точнее — из прогона, а не из истории.
     */
    public List<String> earlierProjects(String conversationId) {
        final LinkedHashSet<String> ordered = new LinkedHashSet<>();
        for (ChatMessageEntity row : chatMessageRepository.findProjectSwitches(conversationId)) {
            final ChatMessageMeta meta = row.getMeta();
            if (meta == null || meta.projectSwitchFrom() == null) {
                continue;
            }
            ordered.add(meta.projectSwitchFrom());
            if (meta.project() != null) {
                ordered.add(meta.project());
            }
        }
        return List.copyOf(ordered);
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
