package io.github.trialiya.kb.service;

import static io.github.trialiya.kb.utils.ChatUtils.buildContext;

import com.google.common.util.concurrent.Striped;
import io.github.trialiya.kb.config.model.SummarizeProperties;
import io.github.trialiya.kb.functions.MessageLookupFunction;
import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import io.github.trialiya.kb.model.tool.ToolData;
import io.github.trialiya.kb.model.tool.ToolInvocationMeta;
import io.github.trialiya.kb.repository.ChatMessageRepository;
import io.github.trialiya.kb.service.ChatMemoryService.PromptRow;
import jakarta.annotation.Nonnull;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.Lock;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.apache.logging.log4j.util.Strings;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@Service
public class SummarizeService implements DisposableBean {

    /**
     * Flat per-message protocol overhead charged by {@code messageChars}, in characters — roughly
     * four tokens at the default {@code chars-per-token}, which is about what a role plus the JSON
     * envelope around one message costs in an OpenAI-shaped request. Deliberately not a configured
     * property: it describes the wire format, not a preference anyone should be tuning.
     */
    private static final int PER_MESSAGE_CHARS = 16;

    private static final String COLLAPSE_HEADER =
            "The following are consecutive summaries of a long conversation:\n";
    private static final String CONTEXT_HEADER =
            "Previous summaries (for context only — do not re-summarize):\n";
    private static final String COLLAPSE_FOOTER =
            """
        Now produce a SINGLE merged summary that combines ALL the previous summaries (above) \
        and the following new messages. The result must be a cohesive summary of the entire conversation so far.""";

    private final ChatClient chatClient;
    private final ChatMessageRepository chatMessageRepository;
    private final ChatMemoryService chatMemoryService;
    private final ExecutorService executorService;
    private final TransactionTemplate transactionTemplate;
    private final SummarizeProperties summarizeProperties;

    private final Striped<Lock> locks = Striped.lock(1024);

    public SummarizeService(
            OpenAiChatModel openAiChatModel,
            ChatMessageRepository chatMessageRepository,
            ChatMemoryService chatMemoryService,
            @Value("classpath:prompt/summarizer.md") Resource summarizerPrompt,
            PlatformTransactionManager transactionManager,
            SummarizeProperties summarizeProperties,
            ContextItemService contextItemService) {
        this.chatClient =
                ChatClient.builder(openAiChatModel)
                        .defaultSystem(summarizerPrompt)
                        .defaultTools(
                                new MessageLookupFunction(
                                        chatMessageRepository, contextItemService))
                        .build();
        this.chatMessageRepository = chatMessageRepository;
        this.chatMemoryService = chatMemoryService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.executorService = Executors.newVirtualThreadPerTaskExecutor();
        this.summarizeProperties = summarizeProperties;
    }

    @Override
    public void destroy() {
        executorService.shutdown();
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public void trySummarize(@Nonnull final String conversationId) {
        executorService.submit(
                () -> {
                    final Lock lock = locks.get(conversationId);
                    lock.lock();
                    try {
                        doSummarize(conversationId);
                    } catch (Exception e) {
                        log.error(
                                "[{}] Summarization failed: {}", conversationId, e.getMessage(), e);
                    } finally {
                        lock.unlock();
                    }
                });
    }

    // -------------------------------------------------------------------------
    // Core summarization logic
    // -------------------------------------------------------------------------

    public void doSummarize(@Nonnull final String conversationId) {
        // 1. The live history as the model receives it. Every row here carries the text that will
        // actually be sent, not the text that happens to be stored: the attachment inventory is
        // appended when history is read and never lands in chat_message.content, so measuring the
        // entity would under-count the window by however long the attachments' summaries are.
        // ChatMemoryService#promptRows is the one place that answers "what does the model see",
        // and it answers it for the prompt and for this budget alike.
        final List<PromptRow> promptRows = chatMemoryService.promptRows(conversationId);

        // Summaries ride along in every request but are not part of the window this round may
        // compress — they are already-compressed past. So they leave the window and take their
        // share of the budget with them, below.
        final List<ChatMessageEntity> existingSummaries =
                promptRows.stream()
                        .map(PromptRow::entity)
                        .filter(ChatMessageEntity::isSummary)
                        .toList();

        // allLive keeps the blank-text TOOL protocol rows: their payloads occupy the model's
        // context on every follow-up request, so they weigh on the budget even though the
        // summarizer prompt never sees them.
        final List<PromptRow> allLive =
                promptRows.stream().filter(row -> !row.entity().isSummary()).toList();

        // liveMessages: the subset used to pick the cutoff and build the LLM prompt — anything
        // whose prompt text is non-blank, or a tool-calls-only ASSISTANT segment (blank text but
        // non-empty invocations). Judged on row.text() — the same text generateSummary sends — not
        // on the stored column: a second way to ask "does this row say anything" is exactly the
        // split this class exists to prevent. Only truly-empty TOOL protocol rows are excluded:
        // their info is already exposed via the owning ASSISTANT segment's invocations/resultGist.
        final List<PromptRow> liveMessages =
                allLive.stream()
                        .filter(
                                row ->
                                        Strings.isNotBlank(row.text())
                                                || (row.entity().getInvocations() != null
                                                        && !row.entity()
                                                                .getInvocations()
                                                                .isEmpty()))
                        .toList();

        // One line per AI reply, so the second half is spelled out only when it says something the
        // first does not: the two differ exactly when the window carries empty TOOL protocol rows,
        // which are context the model pays for but the summarizer prompt never sees.
        final MessageMix liveMix = MessageMix.of(allLive);
        final MessageMix promptMix = MessageMix.of(liveMessages);
        log.info(
                "[{}] Summarization check — live context: {}{}",
                conversationId,
                liveMix,
                liveMix.total() == promptMix.total()
                        ? ""
                        : "; of them prompt-eligible: " + promptMix);

        // 2. Determine the slice to compress: everything before the live tail. Four rules shape the
        // boundary and they are not peers.
        //
        // Three are PREFERENCES about what the tail should hold, and each is only an upper bound on
        // how much may be compressed: at least overlapMessages messages of any kind, at least
        // overlapUserMessages USER messages, and the tail should open on a whole turn. A preference
        // that cannot be satisfied simply does not apply — it never forces the boundary anywhere,
        // which is why none of them needs a fallback branch to rescue it.
        //
        // One is a FLOOR, and it is the only rule that bounds anything: whatever the tail is, it
        // must fit the token budget. The preferences can move the boundary earlier and never
        // later, so on their own they let the window grow to whatever the last few turns produce.
        final int overlapMessages = summarizeProperties.overlapMessages();
        final int overlapUserMessages = summarizeProperties.overlapUserMessages();

        int preferred = liveMessages.size() - overlapMessages;
        preferred =
                Math.min(
                        preferred,
                        userBoundary(liveMessages, overlapUserMessages).orElse(preferred));
        preferred = turnBoundary(liveMessages, preferred).orElse(preferred);

        // The budget takes its share from the summaries first: they are sent with every request
        // just like the window is, so the window's real allowance is what they leave behind.
        final long budgetChars =
                (long) summarizeProperties.tokenThreshold() * summarizeProperties.charsPerToken()
                        - charsOf(promptRows.stream().filter(row -> row.entity().isSummary()));
        final int windowBudgetTokens = (int) (budgetChars / summarizeProperties.charsPerToken());
        final int floor = tokenBudgetCutoff(allLive, liveMessages, budgetChars);

        final int cutoff = Math.max(floor, preferred);
        if (floor > preferred) {
            log.info(
                    "[{}] Live tail is over its {}-token share of the {}-token budget — the"
                            + " preferences stopped at {}, the budget needs {}",
                    conversationId,
                    windowBudgetTokens,
                    summarizeProperties.tokenThreshold(),
                    preferred,
                    floor);
        }
        if (cutoff <= 0) {
            log.info(
                    "[{}] Not enough messages to compress: the live tail must keep {} messages and"
                            + " {} user messages, live={}",
                    conversationId,
                    overlapMessages,
                    overlapUserMessages,
                    MessageMix.of(liveMessages));
            return;
        }

        // Position boundary of the compressible slice: everything with position < cutoffPosition
        // (in allLive, so including interleaved TOOL rows/blank tool-call segments) belongs to
        // this round. Reused both for the token estimate and, below, for marking rows summarized.
        final long cutoffPosition =
                cutoff < liveMessages.size()
                        ? liveMessages.get(cutoff).entity().getPosition()
                        : Long.MAX_VALUE;

        // 3. Decide whether this round is worth running at all. Each threshold measures the thing
        // its own name is about: message-count-threshold the slice that would be compressed,
        // token-threshold the whole live window — the window is what every follow-up request
        // sends to the model, and the property has always been documented as its budget. Asking
        // the budget question through budgetCutoff rather than through a second comparison keeps
        // the trigger and the floor from disagreeing at the boundary: over budget the floor has
        // real work to hand this round, under it there is nothing for the round to free.
        // Both counts include tool call arguments and tool response payloads, not just text —
        // a large tool result occupies the context exactly like a large assistant reply.
        final int sliceTokens = estimateTokens(allLive, cutoffPosition);
        final int messageCountThreshold = summarizeProperties.messageCountThreshold();
        if (cutoff < messageCountThreshold && floor == 0) {
            log.info(
                    "[{}] Skipping summarization — compressible: {} < threshold: {}, and the live"
                            + " window ({} tokens) is within its {}-token share of the budget",
                    conversationId,
                    MessageMix.of(liveMessages.subList(0, cutoff)),
                    messageCountThreshold,
                    estimateTokens(allLive, Long.MAX_VALUE),
                    windowBudgetTokens);
            return;
        }

        final List<PromptRow> toCompress = liveMessages.subList(0, cutoff);

        // The range that will actually be marked summarized. liveMessages excludes empty TOOL
        // protocol rows, so the range must run up to (but not including) the first KEPT message —
        // otherwise the trailing protocol rows of the last compressed turn would stay live and
        // orphaned. When everything is compressed (no kept message), the range must cover those
        // trailing rows too, and only allLive — not toCompress — holds the true last position.
        // Logged rather than toCompress.getLast(), which stops at the last prompt-eligible row and
        // so under-reports every turn that ended in tool traffic.
        final long endPosition =
                cutoffPosition == Long.MAX_VALUE
                        ? allLive.getLast().entity().getPosition()
                        : cutoffPosition - 1;

        log.info(
                "[{}] Compressing positions {}-{}: {}; keeping live: {}",
                conversationId,
                toCompress.getFirst().entity().getPosition(),
                endPosition,
                MessageMix.of(toCompress),
                MessageMix.of(liveMessages.subList(cutoff, liveMessages.size())));

        // 4. Generate summary text via LLM. Collapse existing summaries into one meta-summary
        // if this round's new summary would otherwise push the count to summaryCollapseThreshold.
        final boolean collapseSummaries =
                existingSummaries.size() + 1 >= summarizeProperties.summaryCollapseThreshold();
        final @Nullable String summaryContent =
                generateSummary(
                        conversationId, existingSummaries, toCompress, cutoff, collapseSummaries);
        if (summaryContent == null || summaryContent.isBlank()) {
            log.error(
                    "[{}] Summarization produced an empty result, skipping this round",
                    conversationId);
            return;
        }

        // 5. Build the summary message stored as ASSISTANT context.
        final String summaryText =
                collapseSummaries
                        ? buildMetaSummaryText(summaryContent)
                        : buildSummaryText(
                                summaryContent,
                                toCompress.getFirst().entity().getPosition(),
                                toCompress.getLast().entity().getPosition());

        log.info(
                "[{}] Summarization finished — compressed {} (~{} tokens) into ~{} tokens",
                conversationId,
                MessageMix.of(toCompress),
                sliceTokens,
                summaryText.length() / summarizeProperties.charsPerToken());

        // 6. Persist: mark compressed messages as summarized, insert new summary row.
        persistSummary(
                conversationId,
                toCompress,
                existingSummaries,
                collapseSummaries,
                summaryText,
                endPosition);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * The floor under the cutoff: the earliest boundary whose KEPT tail still fits {@code
     * token-threshold}. Walks back from the newest row adding up characters until the budget is
     * spent — everything at or before the row that overflows it has to be compressed. Returns
     * {@code 0} while the whole window fits, which is also the answer to "is this conversation over
     * budget at all", and {@code doSummarize} uses it as exactly that.
     *
     * <p>This is the only rule that bounds the live window. The two overlap rules can move the
     * boundary earlier and never later, so on their own they let the window grow to whatever the
     * last few turns happen to produce: five questions answered by a thousand tool rows keep all
     * thousand rows live, the compressible slice stays a couple of messages wide, and no round ever
     * runs. The floor is derived from the configured budget rather than from a factor over {@code
     * overlap-messages} — "does the tail still fit" has one honest answer and {@code
     * token-threshold} already states it.
     *
     * <p>The result is deliberately not aligned onto a USER message the way {@link #turnBoundary}
     * asks for: the floor exists for windows where the nearest USER row is far behind, and aligning
     * would hand the budget straight back. Nothing breaks — the tool-pair invariant does not come
     * from alignment at all, see where {@code endPosition} is computed. The newest message always
     * stays live: summarizing the question that was just asked would buy nothing.
     *
     * @param budgetChars what the window may spend, i.e. the configured budget minus what the
     *     summaries already take. Non-positive means the summaries alone are over budget, and every
     *     row overflows at once — the window is squeezed to its newest message, which is the most
     *     this rule can do.
     */
    private static int tokenBudgetCutoff(
            List<PromptRow> allLive, List<PromptRow> liveMessages, long budgetChars) {
        if (liveMessages.isEmpty()) {
            return 0;
        }
        long chars = 0;
        long overflowPosition = 0;
        boolean overflowed = false;
        for (int i = allLive.size() - 1; i >= 0; i--) {
            chars += messageChars(allLive.get(i));
            if (chars > budgetChars) {
                overflowPosition = allLive.get(i).entity().getPosition();
                overflowed = true;
                break;
            }
        }
        if (!overflowed) {
            return 0;
        }
        int cutoff = 0;
        while (cutoff < liveMessages.size()
                && liveMessages.get(cutoff).entity().getPosition() <= overflowPosition) {
            cutoff++;
        }
        return Math.min(cutoff, liveMessages.size() - 1);
    }

    /**
     * Preference: the tail should hold {@code keepUserMessages} USER messages, so the boundary may
     * not go past the N-th USER message counted from the end. Empty when the window does not hold
     * that many — keeping five questions where the conversation only ever had one is not something
     * a boundary can arrange, so the preference stands aside instead of forcing one.
     *
     * <p>Index {@code 0} is a real answer here, not a failure: it says the N-th question from the
     * end opens the window, so this preference would have nothing compressed. The floor is what
     * decides whether the window may stay that way.
     */
    private static OptionalInt userBoundary(List<PromptRow> liveMessages, int keepUserMessages) {
        if (keepUserMessages <= 0) {
            return OptionalInt.empty();
        }
        int seen = 0;
        for (int i = liveMessages.size() - 1; i >= 0; i--) {
            if (liveMessages.get(i).entity().getType() == MessageType.USER
                    && ++seen == keepUserMessages) {
                return OptionalInt.of(i);
            }
        }
        return OptionalInt.empty();
    }

    /**
     * Preference: the tail should open on a whole turn, so the boundary is walked back from {@code
     * upperBound} to the nearest USER message. Empty when there is none to walk back to, and when
     * the bound already covers the whole window (nothing is being kept, so nothing needs opening).
     *
     * <p>This is taste, not protocol. It is tempting to read it as the rule that keeps an ASSISTANT
     * tool-call segment together with its TOOL responses, and the code used to say so — but that
     * invariant is held by position-based marking instead, and holds with or without this
     * preference. Treating it as load-bearing is what once made an unsatisfiable alignment collapse
     * the boundary to zero and need a fallback branch to rescue it.
     */
    private static OptionalInt turnBoundary(List<PromptRow> liveMessages, int upperBound) {
        if (upperBound <= 0 || upperBound >= liveMessages.size()) {
            return OptionalInt.empty();
        }
        for (int i = upperBound; i > 0; i--) {
            if (liveMessages.get(i).entity().getType() == MessageType.USER) {
                return OptionalInt.of(i);
            }
        }
        return OptionalInt.empty();
    }

    /**
     * How many messages a slice holds and of what kind — what the log needs to answer "how much
     * context is this, and whose". {@code toolCalls} counts the individual tool invocations carried
     * by the ASSISTANT segments, not the segments themselves: a single segment can fire several
     * tools, and it is the invocations that fill the context window. {@code other} catches the
     * types that are neither of the three (a live SYSTEM row, say) so the breakdown always adds up
     * to {@code total} — a log line whose numbers silently don't sum is worse than no log line.
     */
    private record MessageMix(
            int total, int user, int assistant, int tool, int other, int toolCalls) {

        private static MessageMix of(List<PromptRow> rows) {
            int user = 0;
            int assistant = 0;
            int tool = 0;
            int other = 0;
            int toolCalls = 0;
            for (PromptRow row : rows) {
                final ChatMessageEntity message = row.entity();
                switch (message.getType()) {
                    case USER -> user++;
                    case ASSISTANT -> assistant++;
                    case TOOL -> tool++;
                    default -> other++;
                }
                if (message.getInvocations() != null) {
                    toolCalls += message.getInvocations().size();
                }
            }
            return new MessageMix(rows.size(), user, assistant, tool, other, toolCalls);
        }

        @Override
        public String toString() {
            return "%d messages (user=%d, assistant=%d, tool=%d%s, tool calls=%d)"
                    .formatted(
                            total,
                            user,
                            assistant,
                            tool,
                            other == 0 ? "" : ", other=%d".formatted(other),
                            toolCalls);
        }
    }

    /**
     * Rough token estimate for messages positioned before {@code beforePosition}: total characters
     * (text + tool_calls arguments + tool response payloads) / charsPerToken. Good enough for a
     * threshold check; no need for a full tokenizer here.
     */
    private int estimateTokens(List<PromptRow> rows, long beforePosition) {
        return (int)
                (charsOf(rows.stream().filter(row -> row.entity().getPosition() < beforePosition))
                        / summarizeProperties.charsPerToken());
    }

    private static long charsOf(Stream<PromptRow> rows) {
        return rows.mapToLong(SummarizeService::messageChars).sum();
    }

    /**
     * What one message costs the request, in characters — measured on the text that will be sent,
     * {@link PromptRow#text()}, not on {@code chat_message.content}. The two differ by the
     * attachment inventory, which is rendered at read time and stored nowhere; counting the stored
     * column instead would leave the whole inventory outside the budget, and an attachment summary
     * has no length limit.
     *
     * <p>Every message also carries protocol framing on top of its payload — its role, the JSON
     * envelope around it, the tool_call_id on a TOOL row — and {@code PER_MESSAGE_CHARS} charges a
     * flat approximation of it. Without that charge an empty TOOL row costs literally nothing and a
     * window of a thousand short rows estimates as nearly free, which is the difference between a
     * token budget that bounds the window and one that can be walked around by splitting the same
     * context across more messages.
     */
    private static long messageChars(PromptRow row) {
        long chars = PER_MESSAGE_CHARS + row.text().length();
        final ToolData toolData = row.entity().getToolData();
        if (toolData != null) {
            if (toolData.toolCalls() != null) {
                for (ToolData.Call call : toolData.toolCalls()) {
                    chars += call.arguments() == null ? 0 : call.arguments().length();
                }
            }
            if (toolData.responses() != null) {
                for (ToolData.Response response : toolData.responses()) {
                    chars += response.responseData() == null ? 0 : response.responseData().length();
                }
            }
        }
        return chars;
    }

    private @Nullable String generateSummary(
            String conversationId,
            List<ChatMessageEntity> existingSummaries,
            List<PromptRow> toCompress,
            int count,
            boolean collapseSummaries) {
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

        prompt.append("Summarize the following ").append(count).append(" messages:\n");
        toCompress.forEach(
                row -> {
                    // row.text() — уже с описью приложенного: она дописывается при чтении истории
                    // и в content не лежит. Без неё summarizer.md требует сохранить то, чего в его
                    // входе нет, — а вместе со сжатым сообщением исчезал бы и единственный след
                    // вложения в диалоге.
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
                .toolContext(buildContext(conversationId))
                .call()
                .content();
    }

    /**
     * Appends a compact "which tools ran here and what they returned" block for a segment, using
     * {@code resultGist} (a short human-readable preview, not the full tool response) — the
     * summarizer needs to know *what happened* during a tool call, not replay its raw payload.
     * Without this the model only sees the assistant's prose and has no idea tools were even
     * invoked, since tool_calls/tool responses live in {@code tool_data}, not in message text.
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

    /** Marks old messages as summarized and inserts the new summary row, atomically. */
    private void persistSummary(
            String conversationId,
            List<PromptRow> oldMessages,
            List<ChatMessageEntity> existingSummaries,
            boolean collapseSummaries,
            String metaSummaryText,
            long endPosition) {
        if (oldMessages.isEmpty()) {
            return;
        }
        final ChatMessageEntity firstMsg =
                collapseSummaries ? existingSummaries.getFirst() : oldMessages.getFirst().entity();
        final ChatMessageEntity lastMsg = oldMessages.getLast().entity();

        transactionTemplate.executeWithoutResult(
                s -> {
                    chatMessageRepository.updateSummarized(
                            conversationId, firstMsg.getPosition(), endPosition);
                    chatMessageRepository.save(
                            new ChatMessageEntity(
                                    0L,
                                    conversationId,
                                    metaSummaryText,
                                    MessageType.ASSISTANT,
                                    lastMsg.getPosition(),
                                    false,
                                    true,
                                    lastMsg.getCreatedAt(),
                                    null));
                });
    }
}
