package io.github.trialiya.kb.service.chat.memory;

import static io.github.trialiya.kb.utils.ChatUtils.context;

import io.github.trialiya.kb.advisor.RoundUsageAdvisor;
import io.github.trialiya.kb.config.model.SummarizeProperties;
import io.github.trialiya.kb.functions.MessageLookupFunction;
import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import io.github.trialiya.kb.model.chat.entity.ChatPendingSummaryEntity;
import io.github.trialiya.kb.model.chat.entity.ChatTopicEntity;
import io.github.trialiya.kb.model.chat.entity.CompactMeta;
import io.github.trialiya.kb.model.chat.entity.RunTokenUsage;
import io.github.trialiya.kb.model.tool.ToolInvocationMeta;
import io.github.trialiya.kb.repository.ChatMessageRepository;
import io.github.trialiya.kb.repository.ChatTopicRepository;
import io.github.trialiya.kb.service.chat.context.ContextItemService;
import io.github.trialiya.kb.service.chat.memory.ChatHistoryService.PromptRow;
import io.github.trialiya.kb.service.chat.memory.SummarizeWindow.MessageMix;
import jakarta.annotation.Nonnull;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

/**
 * Background compression of long conversations. This class is the orchestration only — one round
 * per conversation at a time, the LLM call, the atomic persist; everything about WHERE the boundary
 * goes lives in {@link SummarizeWindow}.
 *
 * <p>Раунд оставляет за собой тот же видимый след, что и {@code /compact}, — строку-плашку со своим
 * замером ({@link SummaryWriter#writeCompacted}). Молча сжимать нельзя по двум причинам сразу:
 * пользователь иначе не узнаёт, что часть разговора модель больше не видит, а счётчик токенов чата
 * продолжает показывать замер прогона, который мерил уже несуществующий контекст.
 *
 * <p>Написанное раунд не применяет — он его паркует ({@link PendingSummaryService}). Когда сжатое
 * начало истории действительно перестанет ехать модели, решает уже не он: подмена начала истории
 * обесценивает кэш промпта у провайдера, и стоит она по-разному в зависимости от того, когда её
 * сделать.
 */
@Slf4j
@Service
public class SummarizeService implements DisposableBean {

    private static final String COLLAPSE_HEADER =
            "The following are consecutive summaries of a long conversation:\n";
    private static final String CONTEXT_HEADER =
            "Previous summaries (for context only — do not re-summarize):\n";
    private static final String COLLAPSE_FOOTER =
            """
        Now produce a SINGLE merged summary that combines ALL the previous summaries (above) \
        and the following new messages. The result must be a cohesive summary of the entire \
        conversation so far, in the section format, merged section by section — carrying over \
        every `## User requests` and `## Artifacts` bullet of the previous summaries unchanged.""";

    private final ChatClient chatClient;
    private final ChatHistoryService chatHistory;
    private final ChatTopicRepository chatTopicRepository;
    private final ExecutorService executorService;
    private final SummaryWriter summaryWriter;
    private final PendingSummaryService pendingSummaries;
    private final SummarizeProperties summarizeProperties;

    public SummarizeService(
            OpenAiChatModel openAiChatModel,
            ChatMessageRepository chatMessageRepository,
            ChatHistoryService chatHistory,
            ChatTopicRepository chatTopicRepository,
            @Value("classpath:prompt/summarizer.md") Resource summarizerPrompt,
            SummaryWriter summaryWriter,
            PendingSummaryService pendingSummaries,
            SummarizeProperties summarizeProperties,
            ContextItemService contextItemService) {
        this.chatClient =
                ChatClient.builder(openAiChatModel)
                        .defaultSystem(summarizerPrompt)
                        .defaultTools(
                                new MessageLookupFunction(
                                        chatMessageRepository, contextItemService))
                        .build();
        this.chatHistory = chatHistory;
        this.chatTopicRepository = chatTopicRepository;
        this.summaryWriter = summaryWriter;
        this.pendingSummaries = pendingSummaries;
        this.executorService = Executors.newVirtualThreadPerTaskExecutor();
        this.summarizeProperties = summarizeProperties;
    }

    @Override
    public void destroy() {
        executorService.shutdown();
    }

    public void trySummarize(@Nonnull final String conversationId) {
        executorService.submit(
                () ->
                        // Замок держит SummaryWriter — он общий с /compact, который сжимает тот же
                        // чат тем же способом и без общего замка успел бы прочитать то же окно.
                        summaryWriter.inConversation(
                                conversationId,
                                () -> {
                                    try {
                                        doSummarize(conversationId);
                                    } catch (Exception e) {
                                        log.error(
                                                "[{}] Summarization failed: {}",
                                                conversationId,
                                                e.getMessage(),
                                                e);
                                    }
                                }));
    }

    public void doSummarize(@Nonnull final String conversationId) {
        // promptRows is the one place that answers "what does the model see": every row carries the
        // text that will be sent, inventory included, not the text that happens to be stored. The
        // prompt below and the character estimate inside SummarizeWindow both measure exactly that
        // — and the estimate is what weighs a slice the provider's measurements do not cover.
        final List<ChatPendingSummaryEntity> parked = pendingSummaries.parked(conversationId);
        final SummarizeWindow window =
                new SummarizeWindow(
                        withParked(chatHistory.promptRows(conversationId), parked),
                        summarizeProperties);

        // The second mix is spelled out only when it differs — that is, when the window carries
        // empty TOOL protocol rows: context the model pays for but the summarizer never sees.
        final MessageMix liveMix = MessageMix.of(window.allLive());
        final MessageMix promptMix = MessageMix.of(window.prompt());
        log.info(
                "[{}] Summarization check — live context: {}{}",
                conversationId,
                liveMix,
                liveMix.total() == promptMix.total()
                        ? ""
                        : "; of them prompt-eligible: " + promptMix);
        if (!window.worthARound()) {
            log.info(
                    "[{}] Skipping summarization — compressible: {}, {}; neither threshold"
                            + " reached ({} messages / {} tokens). Live window: {}",
                    conversationId,
                    MessageMix.of(window.toCompress()),
                    window.sliceTokens(),
                    summarizeProperties.messageCountThreshold(),
                    summarizeProperties.tokenThreshold(),
                    window.windowTokens());
            return;
        }

        final List<PromptRow> toCompress = window.toCompress();
        log.info(
                "[{}] Compressing positions {}-{} ({} reached the threshold): {}, {};"
                        + " keeping live: {}",
                conversationId,
                toCompress.getFirst().entity().getPosition(),
                window.endPosition(),
                window.trigger(),
                MessageMix.of(toCompress),
                window.sliceTokens(),
                MessageMix.of(window.kept()));

        // Collapse existing summaries into one meta-summary if this round's new summary would
        // otherwise push the count to summaryCollapseThreshold.
        final List<ChatMessageEntity> existingSummaries = window.summaries();
        // Пока очередь не применена, схлопывания не бывает: метасводка заменяет собой те сводки,
        // которые перечислила, а у припаркованной замена означала бы потерянный ряд — с ним и её
        // плашку, и деньги её раунда, которых больше нигде нет. Ряды очереди схлопнутся следующим
        // раундом после применения, как обычные, а порог считает только применённые: сводка,
        // которой в промпте ещё нет, его и не удлиняет.
        final boolean collapseSummaries =
                parked.isEmpty()
                        && existingSummaries.size() + 1
                                >= summarizeProperties.summaryCollapseThreshold();
        // Замер на весь раунд, включая обращения tool-цикла: у фоновой суммаризации своей области
        // прогона нет, и без накопителя её токены не попали бы в итог по чату ни одним числом —
        // при том что тратит она столько же, сколько ответ (см. RoundUsageAdvisor).
        final RoundUsageAdvisor roundUsage = new RoundUsageAdvisor();
        final @Nullable String summaryContent =
                generateSummary(
                        conversationId,
                        existingSummaries,
                        toCompress,
                        collapseSummaries,
                        roundUsage);
        if (summaryContent == null || summaryContent.isBlank()) {
            log.error(
                    "[{}] Summarization produced an empty result, skipping this round",
                    conversationId);
            return;
        }

        final String summaryText =
                collapseSummaries
                        ? buildMetaSummaryText(summaryContent)
                        : buildSummaryText(
                                summaryContent,
                                toCompress.getFirst().entity().getPosition(),
                                window.endPosition());

        final RunTokenUsage usage = roundUsage.usage();
        log.info(
                "[{}] Summarization finished — compressed {} ({}) into ~{} tokens;"
                        + " the round itself: input {} ({} from cache), output {}",
                conversationId,
                MessageMix.of(toCompress),
                window.sliceTokens(),
                summaryText.length() / summarizeProperties.charsPerToken(),
                usage.promptTokens(),
                usage.cacheReadTokens(),
                usage.outputTokens());

        parkSummary(
                conversationId,
                toCompress,
                existingSummaries,
                collapseSummaries,
                summaryText,
                summaryContent.length(),
                usage,
                window.endPosition());
    }

    // -------------------------------------------------------------------------
    // The LLM round
    // -------------------------------------------------------------------------

    /**
     * Живое окно, каким его увидел бы раунд, если бы очередь уже применили: ряды, сжатые
     * припаркованными сводками, заменены самими сводками.
     *
     * <p>Без этой подмены очередь останавливала бы суммаризацию до самого применения — сжатый кусок
     * в промпте всё ещё живой, и раунд сжимал бы его второй раз, заплатив за это дважды. С ней
     * раунд идёт как обычно: припаркованное он получает контекстом ({@link #generateSummary}), а
     * сжимает то, что накопилось за ним.
     *
     * <p>Подмена только здесь и только для решения «что сжать». Модели чат по-прежнему возит
     * несжатую историю — в том и смысл отложенного применения, что промпт не меняется, пока за его
     * переписывание берут деньги (см. {@link PendingSummaryService}).
     *
     * <p>Ряды очереди — те же, что запишет применение: тот же текст с обёрткой, та же позиция, то
     * же время, та же мета. Флаг {@code summary} делает их для {@link SummarizeWindow} уже сжатым
     * прошлым — дальше она сама не считает их ни в срезе, ни в весе.
     *
     * <p>Уходят отсюда только ЖИВЫЕ ряды сжатого куска. Применённые сводки остаются все до одной,
     * хотя позиции у них ниже: они уже сжатое прошлое, и, потеряв их, раунд написал бы сводку без
     * начала разговора — а след проектов ({@link ProjectTrace#of}) наследуется по цепочке и
     * оборвался бы вместе с ними.
     */
    private static List<PromptRow> withParked(
            List<PromptRow> rows, List<ChatPendingSummaryEntity> parked) {
        if (parked.isEmpty()) {
            return rows;
        }
        final long compressed = parked.getLast().getEndPosition();
        return Stream.of(
                        rows.stream()
                                .filter(row -> row.entity().isSummary())
                                .filter(row -> row.entity().getPosition() <= compressed),
                        parked.stream().map(SummarizeService::parkedRow),
                        rows.stream().filter(row -> row.entity().getPosition() > compressed))
                .flatMap(stream -> stream)
                .toList();
    }

    /**
     * Припаркованная сводка в виде ряда истории — такого же, каким её запишет применение. Мета
     * оттуда же: в ней спаны проектов, которые следующая сводка обязана унаследовать (см. {@link
     * ProjectTrace#of}), иначе на ней оборвётся весь след сжатой истории.
     */
    private static PromptRow parkedRow(ChatPendingSummaryEntity parked) {
        final ChatMessageEntity entity =
                new ChatMessageEntity(
                        0L,
                        parked.getConversationId(),
                        parked.getText(),
                        MessageType.ASSISTANT,
                        parked.getSummaryPosition(),
                        false,
                        true,
                        parked.getSummaryCreatedAt(),
                        parked.getMeta());
        return new PromptRow(entity, parked.getText());
    }

    private @Nullable String generateSummary(
            String conversationId,
            List<ChatMessageEntity> existingSummaries,
            List<PromptRow> toCompress,
            boolean collapseSummaries,
            RoundUsageAdvisor roundUsage) {
        final StringBuilder prompt = new StringBuilder();

        if (collapseSummaries) {
            log.info(
                    "[{}] Including {} summaries into one meta-summary",
                    conversationId,
                    existingSummaries.size());
            prompt.append(COLLAPSE_HEADER);
        } else if (!existingSummaries.isEmpty()) {
            prompt.append(CONTEXT_HEADER);
        }
        for (int i = 0; i < existingSummaries.size(); i++) {
            prompt.append("Summary ")
                    .append(i + 1)
                    .append(":\n")
                    .append(existingSummaries.get(i).getText())
                    .append("\n\n");
        }

        prompt.append("Summarize the following ").append(toCompress.size()).append(" messages:\n");
        toCompress.forEach(
                row -> {
                    // row.text() already carries the attachment inventory: it is appended at read
                    // time and never stored. Without it summarizer.md would be asked to preserve
                    // what its own input never showed, and the last trace of an attachment would
                    // vanish together with the message that carried it.
                    prompt.append("[msg:")
                            .append(row.entity().getPosition())
                            .append("] ")
                            .append(row.entity().getMessageType())
                            .append(": <msg>\n")
                            .append(row.text())
                            .append("\n</msg>\n");
                    appendToolCalls(prompt, row.entity().getInvocations());
                });
        if (collapseSummaries) {
            prompt.append(COLLAPSE_FOOTER);
        }

        return chatClient
                .prompt(prompt.toString())
                .toolContext(context(conversationId).build())
                .advisors(a -> a.advisors(roundUsage))
                .call()
                .content();
    }

    /**
     * Appends a compact "which tools ran here and what they returned" block, using {@code
     * resultGist} — a short preview, not the raw payload: the summarizer needs to know *what
     * happened*, not to replay it. Without this the model sees only the assistant's prose and has
     * no idea tools ran at all, since tool_calls/responses live in {@code tool_data}, not in text.
     */
    private static void appendToolCalls(
            StringBuilder prompt, @Nullable List<ToolInvocationMeta> invocations) {
        if (invocations == null || invocations.isEmpty()) {
            return;
        }
        prompt.append("Tools called in this segment:\n");
        for (ToolInvocationMeta invocation : invocations) {
            prompt.append("  - ")
                    .append(invocation.name())
                    .append("(")
                    .append(invocation.arguments())
                    .append(") -> ")
                    .append(invocation.status());
            if (invocation.error() != null) {
                prompt.append(", error: ").append(invocation.error());
            } else if (invocation.resultGist() != null) {
                prompt.append(": ").append(invocation.resultGist());
            }
            prompt.append("\n");
        }
    }

    /**
     * {@code lastPosition} is the last position this round <em>marks</em>, not the last one the
     * summarizer read: empty TOOL protocol rows trail the final compressed turn and are marked with
     * it, so "continue from N+1" has to clear them or it points at a summarized row.
     */
    private String buildSummaryText(String content, long firstPosition, long lastPosition) {
        return "Earlier conversation summary (messages "
                + firstPosition
                + "-"
                + lastPosition
                + "):\n"
                + "<summary>\n"
                + content
                + "\n</summary>\n"
                + "Continue from message "
                + (lastPosition + 1)
                + " onward. "
                + "Treat the summary as authoritative context.";
    }

    private String buildMetaSummaryText(String content) {
        return "Merged conversation summary:\n"
                + "<summary>\n"
                + content
                + "\n</summary>\n"
                + "Treat this as authoritative context for the entire conversation so far.";
    }

    /**
     * Паркует написанное до подходящего момента: разметка сжатого куска, строка-сводка и видимая
     * плашка появятся вместе, когда {@link PendingSummaryService} решит, что подмена начала истории
     * обойдётся дёшево.
     *
     * <p>Плашка — единственное, что о фоновом сжатии вообще можно узнать: сводка модели не
     * показывается, а ряды, которые она заменила, с виду остаются прежними. Отличает её вид {@link
     * CompactMeta.Kind#SUMMARIZE} — сжато начало истории, а не весь контекст, и живой хвост под
     * плашкой остаётся.
     *
     * @param summaryChars длина документа модели — без обёртки, в которую он попадает в {@code
     *     metaSummaryText}: плашка показывает это число рядом с самим документом
     * @param usage токены раунда; пустой замер в мету не идёт — «неизвестно» это не ноль
     */
    private void parkSummary(
            String conversationId,
            List<PromptRow> oldMessages,
            List<ChatMessageEntity> existingSummaries,
            boolean collapseSummaries,
            String metaSummaryText,
            int summaryChars,
            RunTokenUsage usage,
            long endPosition) {
        if (oldMessages.isEmpty()) {
            return;
        }
        final ChatMessageEntity firstMsg =
                collapseSummaries ? existingSummaries.getFirst() : oldMessages.getFirst().entity();
        final ChatMessageEntity lastMsg = oldMessages.getLast().entity();

        pendingSummaries.park(
                conversationId,
                new SummaryWriter.SummaryRow(
                        conversationId,
                        firstMsg.getPosition(),
                        endPosition,
                        lastMsg.getPosition(),
                        lastMsg.getCreatedAt(),
                        metaSummaryText,
                        // Which repository each compressed stretch belongs to: the markers that
                        // said so are being summarized away, and the summary row is what keeps
                        // the trace — this round's carriers on top of the previous summary's.
                        ProjectTrace.of(
                                existingSummaries,
                                oldMessages.stream().map(PromptRow::entity).toList(),
                                () ->
                                        chatTopicRepository
                                                .findById(conversationId)
                                                .map(ChatTopicEntity::getProject)
                                                .orElse(null),
                                endPosition)),
                new SummaryWriter.CompactStats(
                        CompactMeta.Kind.SUMMARIZE,
                        oldMessages.size(),
                        summaryChars,
                        usage.isEmpty() ? null : usage,
                        null));
    }
}
