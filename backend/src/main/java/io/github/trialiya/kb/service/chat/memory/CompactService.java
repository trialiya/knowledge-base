package io.github.trialiya.kb.service.chat.memory;

import static io.github.trialiya.kb.model.chat.dto.ChatEventType.COMPACT_DONE;
import static io.github.trialiya.kb.model.chat.dto.ChatEventType.COMPACT_ERROR;
import static io.github.trialiya.kb.model.chat.dto.ChatEventType.COMPACT_STARTED;

import io.github.trialiya.kb.config.ChatModelRegistry;
import io.github.trialiya.kb.model.chat.dto.CompactPayload;
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
 * <p>Команда в историю не попадает: сохранённой она стала бы первой строкой уже сжатого чата и
 * поехала бы модели в каждом следующем запросе. Вместо неё в конец запроса встаёт инструкция,
 * собранная здесь, — с хвостом команды в роли фокуса и справкой о самом чате.
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

    /**
     * Занимает чат, снимает с него окно и запускает сжатие в фоне — HTTP-запрос не держим: раунд
     * идёт по всему контексту сразу и живёт десятки секунд, а таймаут прокси посреди него оставил
     * бы вкладку с висящей блокировкой при работающем сжатии.
     *
     * <p>Окно снимается ЗДЕСЬ, синхронно, а не в фоновой задаче: только так «сжимать нечего»
     * остаётся ответом этого запроса (422), а не событием, которое некому показать. Гонки с
     * дописыванием истории при этом нет — чат уже занят.
     *
     * @param model id модели, на которой пойдёт раунд, уже разрешённый вызывающим; {@code null} —
     *     модель из конфигурации
     * @return runId занятой операции — им же вкладки помечают чат занятым
     */
    public String start(
            String conversationId, @Nullable String instructions, @Nullable String model) {
        final String runId = chatRunService.claim(conversationId);
        final List<PromptRow> rows;
        try {
            // Оборванный прошлый прогон мог оставить в хвосте assistant.tool_calls без TOOL-ответа
            // — такой диалог модель отвергает целиком, а здесь он уехал бы ей весь.
            chatHistory.repairDanglingToolCalls(conversationId);
            rows = chatHistory.promptRows(conversationId);
            if (nothingToCompact(rows)) {
                throw new ResponseStatusException(
                        HttpStatus.UNPROCESSABLE_CONTENT, "Nothing to compact");
            }
        } catch (RuntimeException e) {
            chatRunService.release(conversationId, runId);
            throw e;
        }
        events.publish(conversationId, COMPACT_STARTED, runId, null, null);
        try {
            executor.execute(() -> run(conversationId, runId, rows, instructions, model));
        } catch (RuntimeException e) {
            chatRunService.release(conversationId, runId);
            throw e;
        }
        return runId;
    }

    /**
     * Сжимать нечего, когда живого контекста нет вовсе или он уже состоит из одной сводки: сжатие
     * сводки в сводку — это раунд, который ничего не экономит и при этом теряет детали.
     */
    private static boolean nothingToCompact(List<PromptRow> rows) {
        return rows.stream().filter(row -> !row.entity().isSummary()).findAny().isEmpty();
    }

    private void run(
            String conversationId,
            String runId,
            List<PromptRow> rows,
            @Nullable String instructions,
            @Nullable String model) {
        try {
            final CompactPayload payload = compact(conversationId, rows, instructions, model);
            events.publish(conversationId, COMPACT_DONE, runId, null, payload);
        } catch (Exception e) {
            log.error("[{}] Compaction failed: {}", conversationId, e.getMessage(), e);
            events.publish(
                    conversationId,
                    COMPACT_ERROR,
                    runId,
                    null,
                    Map.of("message", String.valueOf(e.getMessage())));
        } finally {
            chatRunService.release(conversationId, runId);
        }
    }

    /**
     * Сам раунд: окно → модель → строка-сводка вместо всего окна. Публичный, чтобы его можно было
     * позвать без фонового пуска и без событий.
     */
    public CompactPayload compact(
            String conversationId,
            List<PromptRow> rows,
            @Nullable String instructions,
            @Nullable String model) {
        final List<Message> history = rows.stream().map(PromptRow::toMessage).toList();
        final long startPosition = rows.getFirst().entity().getPosition();
        final long endPosition = rows.getLast().entity().getPosition();
        log.info(
                "[{}] Compacting positions {}-{}: {} messages, ~{} chars, model {}",
                conversationId,
                startPosition,
                endPosition,
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
            // Разметить окно сжатым, не сохранив сводку, значит стереть чат целиком.
            throw new IllegalStateException("The model returned an empty compaction");
        }

        final ChatMessageEntity last = rows.getLast().entity();
        summaryWriter.write(
                new SummaryWriter.SummaryRow(
                        conversationId,
                        startPosition,
                        endPosition,
                        last.getPosition(),
                        last.getCreatedAt(),
                        summaryText(content),
                        SummaryWriter.lastProject(rows.stream().map(PromptRow::entity))));
        log.info(
                "[{}] Compaction finished: {} messages -> {} chars",
                conversationId,
                rows.size(),
                content.length());
        return new CompactPayload(rows.size(), content.length());
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
