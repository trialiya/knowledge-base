package io.github.trialiya.kb.service.chat.memory;

import static io.github.trialiya.kb.model.chat.dto.ChatEventType.COMPACT_DONE;
import static io.github.trialiya.kb.model.chat.dto.ChatEventType.COMPACT_ERROR;
import static io.github.trialiya.kb.model.chat.dto.ChatEventType.COMPACT_STARTED;
import static io.github.trialiya.kb.model.chat.dto.ChatEventType.USER_MESSAGE;

import io.github.trialiya.kb.config.ChatModelRegistry;
import io.github.trialiya.kb.model.chat.dto.CompactPayload;
import io.github.trialiya.kb.model.chat.dto.UserMessagePayload;
import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import io.github.trialiya.kb.model.chat.entity.ChatTopicEntity;
import io.github.trialiya.kb.repository.ChatTopicRepository;
import io.github.trialiya.kb.service.chat.memory.ChatHistoryService.PromptRow;
import io.github.trialiya.kb.service.chat.run.ChatEventService;
import io.github.trialiya.kb.service.chat.run.ChatRunService;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

/**
 * Сжатие контекста по команде {@code /compact} — в отличие от фоновой суммаризации ({@link
 * SummarizeService}) её просит пользователь, и сжимает она весь живой контекст целиком, живого
 * хвоста не оставляя.
 *
 * <p><b>Историю никто не пересказывает.</b> Модель получает окно ровно тем же списком сообщений,
 * каким его получает чат ({@link ChatHistoryService#promptMessages}): протокольные {@code
 * tool_calls} с полными аргументами и TOOL-строки с полными результатами — внутри. Поэтому здесь
 * нет ни рендера истории в текст, ни усечения результатов до гистов, которыми живёт суммаризатор:
 * то, что уезжает модели, и есть история.
 *
 * <p><b>Клиент строится здесь и намеренно голый</b> — без инструментов и, что важнее, без
 * адвайзеров памяти: клиент из {@code ChatClientRegistry} подмешал бы то же окно вторым слоем, а
 * ответ записал бы в историю обычной репликой ассистента.
 *
 * <p><b>Команда остаётся в истории, но не участвует в сжатии.</b> Сообщение {@code /compact
 * <текст>} сохраняется обычной USER-строкой — так же видимой, как любая другая реплика, — но само
 * сжатие получает окно ровно таким, каким оно было ДО этого сообщения: команда не материал для
 * сжатия, а управляющий сигнал. Модели вместо неё в конец запроса уходит собранная здесь инструкция
 * — с хвостом команды в роли фокуса и справкой о самом чате. По завершении раунда позиция самой
 * команды попадает в тот же размеченный {@code summarized}-диапазон, что и сжатое окно: дальше она
 * видна пользователю в истории, но перестаёт ехать модели — как и всё, что раунд заменил сводкой.
 */
@Slf4j
@Service
public class CompactService {

    private final ChatModelRegistry chatModelRegistry;
    private final ChatHistoryService chatHistory;
    private final ChatTopicRepository chatTopicRepository;
    private final SummaryWriter summaryWriter;
    private final ChatRunService chatRunService;
    private final ChatEventService events;
    private final Resource compactorPrompt;
    private final Executor executor;

    public CompactService(
            ChatModelRegistry chatModelRegistry,
            ChatHistoryService chatHistory,
            ChatTopicRepository chatTopicRepository,
            SummaryWriter summaryWriter,
            ChatRunService chatRunService,
            ChatEventService events,
            @Value("classpath:prompt/compactor.md") Resource compactorPrompt,
            @Qualifier("chatRunExecutor") Executor executor) {
        this.chatModelRegistry = chatModelRegistry;
        this.chatHistory = chatHistory;
        this.chatTopicRepository = chatTopicRepository;
        this.summaryWriter = summaryWriter;
        this.chatRunService = chatRunService;
        this.events = events;
        this.compactorPrompt = compactorPrompt;
        this.executor = executor;
    }

    /** {@code runId} занятой операции и id сохранённой команды — параллель {@code StartedRun}. */
    public record StartedCompact(String runId, Long messageId) {}

    /**
     * Занимает чат, сохраняет команду и запускает сжатие в фоне — HTTP-запрос не держим: раунд идёт
     * по всему контексту сразу и живёт десятки секунд, а таймаут прокси посреди него оставил бы
     * вкладку с висящей блокировкой при работающем сжатии.
     *
     * <p>Команда сохраняется ЗДЕСЬ, синхронно, а не в фоновой задаче: только так «сжимать нечего»
     * остаётся ответом этого запроса (422, без сохранённого сообщения — команда, которая ничего не
     * сделала, не должна маячить в истории), а сама команда получает {@code id} сразу, не дожидаясь
     * фонового раунда. Гонки с дописыванием истории при этом нет — чат уже занят.
     *
     * <p>Окно здесь читается только ради этой проверки: сжимаемое окно снимает уже сам раунд, под
     * общим с фоновой суммаризацией замком (см. {@link #run}).
     *
     * @param text сообщение {@code /compact <текст>} целиком — сохраняется как есть
     * @param model id модели, на которой пойдёт раунд, уже разрешённый вызывающим; {@code null} —
     *     модель из конфигурации
     * @param clientMsgId id вкладки-отправителя — тот же смысл, что и у {@code POST /runs}: своё
     *     эхо {@code USER_MESSAGE} вкладка гасит по нему, не дожидаясь второго пузыря
     * @return runId занятой операции и id сохранённой команды
     */
    public StartedCompact start(
            String conversationId,
            String text,
            @Nullable String instructions,
            @Nullable String model,
            @Nullable String clientMsgId) {
        final String runId = chatRunService.claim(conversationId);
        final ChatMessageEntity commandRow;
        try {
            // Оборванный прошлый прогон мог оставить в хвосте assistant.tool_calls без TOOL-ответа
            // — такой диалог модель отвергает целиком, а здесь он уехал бы ей весь.
            chatHistory.repairDanglingToolCalls(conversationId);
            if (nothingToCompact(chatHistory.promptRows(conversationId))) {
                throw new ResponseStatusException(
                        HttpStatus.UNPROCESSABLE_CONTENT, "Nothing to compact");
            }
            commandRow = chatHistory.saveUserMessage(conversationId, text, List.of(), null);
        } catch (RuntimeException e) {
            chatRunService.release(conversationId, runId);
            throw e;
        }
        // Эхо для остальных вкладок — тот же payload, что и у обычного вопроса, поэтому фронту не
        // нужен отдельный обработчик: команда встаёт в ленту точно так же, как любое сообщение.
        events.publish(
                conversationId,
                USER_MESSAGE,
                runId,
                clientMsgId,
                new UserMessagePayload(
                        commandRow.getId(),
                        commandRow.getContent(),
                        commandRow.getCreatedAt(),
                        commandRow.getContextItems(),
                        null,
                        null));
        events.publish(conversationId, COMPACT_STARTED, runId, null, null);
        try {
            executor.execute(() -> run(conversationId, runId, commandRow, instructions, model));
        } catch (RuntimeException e) {
            // COMPACT_STARTED уже ушёл всем вкладкам, и своя — та, что получит здесь ошибку —
            // уже под блокировкой. Снять её ответом на этот запрос нельзя: остальные вкладки
            // остались бы на плашке «сжимаю…» навсегда. Значит, гасим тем же событием, каким
            // гасит упавший раунд.
            failed(conversationId, runId, e);
            chatRunService.release(conversationId, runId);
            throw e;
        }
        return new StartedCompact(runId, commandRow.getId());
    }

    /**
     * Сжимать нечего, когда живого контекста нет вовсе или он уже состоит из одной сводки: сжатие
     * сводки в сводку — это раунд, который ничего не экономит и при этом теряет детали.
     */
    private static boolean nothingToCompact(List<PromptRow> rows) {
        return rows.stream().filter(row -> !row.entity().isSummary()).findAny().isEmpty();
    }

    /**
     * Фоновая обёртка раунда: замок, окно, сжатие, событие исхода, освобождение чата.
     *
     * <p>Замок общий с фоновой суммаризацией ({@link SummaryWriter#inConversation}) и обязан
     * охватывать чтение окна, а не только запись сводки. Занятость чата тут не помогает: фоновый
     * раунд стартует по RUN_DONE, вне занятого слота, и без общего замка успел бы прочитать то же
     * окно и записать вторую сводку поверх материала, который эта уже заменила.
     *
     * <p>Поэтому окно снимается ЗДЕСЬ, под замком, а не переносится из {@link #start}: та читала
     * его только ради ответа «сжимать нечего». Из окна выбрасывается всё от позиции команды и
     * дальше — сама команда не материал для сжатия, а сигнал к нему, и попади она в окно, раунд
     * принял бы собственный вызов за часть разговора.
     */
    private void run(
            String conversationId,
            String runId,
            ChatMessageEntity commandRow,
            @Nullable String instructions,
            @Nullable String model) {
        try {
            summaryWriter.inConversation(
                    conversationId,
                    () -> {
                        final List<PromptRow> rows =
                                chatHistory.promptRows(conversationId).stream()
                                        .filter(
                                                row ->
                                                        row.entity().getPosition()
                                                                < commandRow.getPosition())
                                        .toList();
                        if (nothingToCompact(rows)) {
                            // Пока команда ждала своей очереди, окно сжал кто-то другой.
                            throw new IllegalStateException("Nothing left to compact");
                        }
                        final CompactPayload payload =
                                compact(conversationId, rows, commandRow, instructions, model);
                        events.publish(conversationId, COMPACT_DONE, runId, null, payload);
                    });
        } catch (Exception e) {
            failed(conversationId, runId, e);
        } finally {
            chatRunService.release(conversationId, runId);
        }
    }

    /** Сжатие не состоялось: пишем в лог и снимаем блокировку со всех вкладок разом. */
    private void failed(String conversationId, String runId, Exception e) {
        log.error("[{}] Compaction failed: {}", conversationId, e.getMessage(), e);
        events.publish(
                conversationId,
                COMPACT_ERROR,
                runId,
                null,
                Map.of("message", String.valueOf(e.getMessage())));
    }

    /**
     * Сам раунд: окно → модель → строка-сводка вместо всего окна. Публичный, чтобы его можно было
     * позвать без фонового пуска и без событий.
     *
     * @param rows окно, которое уходит модели — БЕЗ {@code commandRow}: команда не часть сжимаемого
     *     разговора
     * @param commandRow уже сохранённая команда {@code /compact}; в модель не попадает, но её
     *     позиция замыкает размеченный {@code summarized}-диапазон и становится позицией сводки —
     *     после раунда команда так же не едет модели, как и всё, что она сжала
     */
    public CompactPayload compact(
            String conversationId,
            List<PromptRow> rows,
            ChatMessageEntity commandRow,
            @Nullable String instructions,
            @Nullable String model) {
        final List<Message> history = rows.stream().map(PromptRow::toMessage).toList();
        final long startPosition = rows.getFirst().entity().getPosition();
        final long oldEndPosition = rows.getLast().entity().getPosition();
        log.info(
                "[{}] Compacting positions {}-{} (command at {}): {} messages, ~{} chars, model {}",
                conversationId,
                startPosition,
                oldEndPosition,
                commandRow.getPosition(),
                rows.size(),
                rows.stream().mapToInt(row -> row.text().length()).sum(),
                model == null ? "default" : model);

        ChatClient.ChatClientRequestSpec spec =
                ChatClient.builder(chatModelRegistry.forModel(model))
                        .defaultSystem(compactorPrompt)
                        .build()
                        .prompt()
                        .messages(history)
                        .user(instruction(conversationId, rows, instructions));
        if (model != null) {
            spec = spec.options(OpenAiChatOptions.builder().model(model));
        }
        final @Nullable String content = spec.call().content();
        if (content == null || content.isBlank()) {
            // Разметить окно сжатым, не сохранив сводку, значит стереть чат целиком. Сама команда
            // при этом уже сохранена и никуда не денется — останется в истории неотвеченной, как
            // любой упавший вопрос.
            throw new IllegalStateException("The model returned an empty compaction");
        }

        summaryWriter.write(
                new SummaryWriter.SummaryRow(
                        conversationId,
                        startPosition,
                        // Диапазон захватывает и саму команду — не только сжатое окно, — поэтому
                        // дальше она видна в истории, но модели больше не едет.
                        commandRow.getPosition(),
                        commandRow.getPosition(),
                        commandRow.getCreatedAt(),
                        summaryText(content),
                        SummaryWriter.lastProject(rows.stream().map(PromptRow::entity))));
        log.info(
                "[{}] Compaction finished: {} messages -> {} chars",
                conversationId,
                rows.size() + 1,
                content.length());
        return new CompactPayload(rows.size() + 1, content.length());
    }

    /**
     * Инструкция, которая встаёт последним сообщением запроса — на месте невыполненной команды
     * пользователя. Справка о чате здесь не украшение: сжатое окно останется единственной памятью
     * разговора, а какому проекту принадлежат пути в нём и на каком языке шёл диалог, из самих
     * сообщений видно не всегда.
     */
    private String instruction(
            String conversationId, List<PromptRow> rows, @Nullable String instructions) {
        final @Nullable ChatTopicEntity chat =
                chatTopicRepository.findById(conversationId).orElse(null);
        final StringBuilder prompt = new StringBuilder();
        prompt.append(
                """
                Everything above this message is the conversation to compact. Replace it with one \
                document, in the section format of your instructions, and answer with that \
                document only — no preamble, no closing remark, no question back.

                About this conversation:
                """);
        append(prompt, "Topic", chat == null ? null : chat.getDisplayTopic());
        append(prompt, "Project", chat == null ? null : chat.getProject());
        append(prompt, "Assistant mode", chat == null ? null : chat.getMode());
        prompt.append("- Messages above: ").append(rows.size()).append('\n');
        prompt.append("- Of them USER messages: ")
                .append(countOf(rows, MessageType.USER))
                .append(" (`## User requests` must have exactly this many bullets)\n");
        prompt.append("- Of them tool protocol messages: ")
                .append(countOf(rows, MessageType.TOOL))
                .append('\n');
        if (StringUtils.hasText(instructions)) {
            prompt.append(
                            """

                            The user asked to focus the compaction on the following. Give this \
                            material more detail than anything else and never let the focus cut a \
                            section short: everything else still has to survive, in full section \
                            format.
                            <focus>
                            """)
                    .append(instructions.strip())
                    .append("\n</focus>\n");
        }
        return prompt.toString();
    }

    private static void append(StringBuilder prompt, String label, @Nullable String value) {
        if (StringUtils.hasText(value)) {
            prompt.append("- ").append(label).append(": ").append(value).append('\n');
        }
    }

    private static long countOf(List<PromptRow> rows, MessageType type) {
        return rows.stream().filter(row -> row.entity().getMessageType() == type).count();
    }

    /**
     * Обёртка вокруг ответа модели — та же роль, что у заголовка фоновой сводки: сказать модели,
     * что перед ней не реплика ассистента, а вся память разговора. Диапазона «продолжай с N» здесь
     * нет намеренно — продолжать неоткуда, живого хвоста после сжатия не остаётся.
     */
    private static String summaryText(String content) {
        return "Compacted conversation summary (requested by the user):\n"
                + "<summary>\n"
                + content
                + "\n</summary>\n"
                + "Treat this as authoritative context for the entire conversation so far: the"
                + " messages it covers are no longer in the context and cannot be re-read.";
    }
}
