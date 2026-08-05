package io.github.trialiya.kb.service;

import static io.github.trialiya.kb.utils.ChatUtils.buildContext;

import com.google.common.util.concurrent.Striped;
import io.github.trialiya.kb.config.model.SummarizeProperties;
import io.github.trialiya.kb.functions.MessageLookupFunction;
import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import io.github.trialiya.kb.model.tool.ToolData;
import io.github.trialiya.kb.model.tool.ToolInvocationMeta;
import io.github.trialiya.kb.repository.ChatMessageRepository;
import jakarta.annotation.Nonnull;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.Lock;
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
    private final ExecutorService executorService;
    private final TransactionTemplate transactionTemplate;
    private final SummarizeProperties summarizeProperties;
    private final ContextItemService contextItemService;

    private final Striped<Lock> locks = Striped.lock(1024);

    public SummarizeService(
            OpenAiChatModel openAiChatModel,
            ChatMessageRepository chatMessageRepository,
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
        this.contextItemService = contextItemService;
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
        // 1. Fetch all live (non-summarized) rows, excluding summary rows. This includes blank-text
        // TOOL protocol rows with no invocations — their payloads still occupy the model's context
        // window on every follow-up request, so they must count toward the token estimate below
        // even though they're excluded from liveMessages/the prompt.
        final List<ChatMessageEntity> allLive =
                chatMessageRepository
                        .findChatMessageByConversationIdAndSummarizedFalseOrderByCreatedAtAscPositionAsc(
                                conversationId)
                        .stream()
                        .filter(m -> !m.isSummary())
                        .toList();

        // liveMessages: the subset used to pick the cutoff and build the LLM prompt — anything
        // with text, or a tool-calls-only ASSISTANT segment (blank text but non-empty invocations).
        // Only truly-empty TOOL protocol rows are excluded: their info is already exposed via the
        // owning ASSISTANT segment's invocations/resultGist.
        final List<ChatMessageEntity> liveMessages =
                allLive.stream()
                        .filter(
                                m ->
                                        Strings.isNotBlank(m.getText())
                                                || (m.getInvocations() != null
                                                        && !m.getInvocations().isEmpty()))
                        .toList();

        log.info(
                "[{}] Summarization check — live context: {}; of them prompt-eligible: {}",
                conversationId,
                MessageMix.of(allLive),
                MessageMix.of(liveMessages));

        // 2. Determine the slice to compress: everything before the live tail. Three rules shape
        // the boundary and they are not peers. Two are preferences about what the tail should
        // hold — at least overlapMessages messages of any kind AND at least overlapUserMessages
        // USER messages — so the earlier of those two boundaries wins. The count rule alone would
        // let a single question followed by a long tool marathon push the user's own recent
        // questions into the summary; the user rule alone would let a burst of short questions
        // shrink the live tail to almost nothing. The third rule, applied below, is not a
        // preference but a floor: whatever the tail is, it must still fit the token budget.
        final int overlapMessages = summarizeProperties.overlapMessages();
        final int overlapUserMessages = summarizeProperties.overlapUserMessages();
        final int countCutoff = liveMessages.size() - overlapMessages;
        final int userCutoff = userMessageCutoff(liveMessages, overlapUserMessages);
        final int rawCutoff = Math.min(countCutoff, userCutoff);

        // The first KEPT message must be a USER message: assistant tool-call segments and their
        // TOOL responses form indivisible pairs, so we only compress whole turns. Otherwise a
        // kept TOOL row could lose its assistant counterpart (the model rejects orphaned tool
        // messages). userCutoff already lands on one; countCutoff generally does not.
        int cutoff = rawCutoff;
        while (cutoff > 0
                && cutoff < liveMessages.size()
                && liveMessages.get(cutoff).getType() != MessageType.USER) {
            cutoff--;
        }
        if (cutoff <= 0 && countCutoff > 0) {
            // Ни одно USER-сообщение не может открыть хвост, хотя сообщений уже больше окна. Причин
            // три, и снаружи они неразличимы: в окне нет ни одного USER (один вопрос → длинный
            // tool-марафон); USER-сообщений меньше, чем требует overlap-user-messages; либо все они
            // лежат позже границы по числу сообщений. Без запасного варианта сжатие не запустилось
            // бы никогда, и контекст рос бы неограниченно, поэтому здесь правило по числу
            // USER-сообщений отступает: удержать их столько, сколько диалог не содержит, всё равно
            // нельзя. Резать по countCutoff безопасно: первым ОСТАВЛЕННЫМ окажется текстовый
            // ASSISTANT-сегмент (протокольные TOOL-строки пусты и в liveMessages не входят),
            // а пометка summarized идёт по позициям — парные TOOL-строки уходят в summary
            // вместе со своими assistant-сегментами, ответ на tool_calls сегмента-границы
            // остаётся живым вместе с ним.
            log.info(
                    "[{}] No USER message can open the live tail (window: {}; overlap rules ask for"
                            + " {} messages and {} user messages) — falling back to the"
                            + " message-count boundary {}",
                    conversationId,
                    MessageMix.of(liveMessages),
                    overlapMessages,
                    overlapUserMessages,
                    countCutoff);
            cutoff = countCutoff;
        }

        // Both preferences may only move the boundary earlier, so on their own they bound nothing:
        // five questions that produced a thousand tool rows keep all thousand live for ever. The
        // budget floor is what actually caps the window — it moves the boundary back to wherever
        // the kept tail starts fitting the token budget, and it outranks both preferences because
        // a tail the model cannot be sent is worth less than the questions kept inside it.
        final int budgetCutoff = tokenBudgetCutoff(allLive, liveMessages);
        if (budgetCutoff > 0 && budgetCutoff > cutoff) {
            log.info(
                    "[{}] Live tail is over the {}-token budget — the overlap rules stopped at {},"
                            + " the budget needs {}",
                    conversationId,
                    summarizeProperties.tokenThreshold(),
                    cutoff,
                    budgetCutoff);
            cutoff = budgetCutoff;
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
                        ? liveMessages.get(cutoff).getPosition()
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
        if (cutoff < messageCountThreshold && budgetCutoff == 0) {
            log.info(
                    "[{}] Skipping summarization — compressible: {} < threshold: {}, and the live"
                            + " window ({} tokens) is within the {}-token budget",
                    conversationId,
                    MessageMix.of(liveMessages.subList(0, cutoff)),
                    messageCountThreshold,
                    estimateTokens(allLive, Long.MAX_VALUE),
                    summarizeProperties.tokenThreshold());
            return;
        }

        final List<ChatMessageEntity> toCompress = liveMessages.subList(0, cutoff);

        log.info(
                "[{}] Compressing positions {}-{}: {}; keeping live: {}",
                conversationId,
                toCompress.getFirst().getPosition(),
                toCompress.getLast().getPosition(),
                MessageMix.of(toCompress),
                MessageMix.of(liveMessages.subList(cutoff, liveMessages.size())));

        // 4. Load existing summaries to give the LLM prior context.
        final List<ChatMessageEntity> existingSummaries =
                chatMessageRepository
                        .findChatMessageByConversationIdAndSummarizedFalseAndSummaryTrueOrderByCreatedAtAscPositionAsc(
                                conversationId);

        // 5. Generate summary text via LLM. Collapse existing summaries into one meta-summary
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

        // 6. Build the summary message stored as ASSISTANT context.
        final String summaryText =
                collapseSummaries
                        ? buildMetaSummaryText(summaryContent)
                        : buildSummaryText(
                                summaryContent,
                                toCompress.getFirst().getPosition(),
                                toCompress.getLast().getPosition());

        log.info(
                "[{}] Summarization finished — compressed {} (~{} tokens) into ~{} tokens",
                conversationId,
                MessageMix.of(toCompress),
                sliceTokens,
                summaryText.length() / summarizeProperties.charsPerToken());

        // 7. Persist: mark compressed messages as summarized, insert new summary row.
        // liveMessages still excludes empty TOOL protocol rows, so the marked range must run up to
        // (but not including) the first KEPT message — otherwise the trailing protocol rows of the
        // last compressed turn would stay live and orphaned. When everything is compressed (no kept
        // message), the range must cover trailing protocol rows too — allLive, not toCompress,
        // holds
        // the true last position.
        final long endPosition =
                cutoffPosition == Long.MAX_VALUE
                        ? allLive.getLast().getPosition()
                        : cutoffPosition - 1;
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
     * <p>The result is deliberately not aligned onto a USER message the way the overlap rules are:
     * the floor exists for windows where the nearest USER row is far behind, and aligning would
     * hand the budget straight back. It is safe for the same reason the count-boundary fallback in
     * {@code doSummarize} is safe — only truly-empty TOOL rows are missing from {@code
     * liveMessages}, and rows are marked summarized by position, so a tool pair is never split
     * across the boundary. The newest message always stays live: summarizing the question that was
     * just asked would buy nothing.
     */
    private int tokenBudgetCutoff(
            List<ChatMessageEntity> allLive, List<ChatMessageEntity> liveMessages) {
        if (liveMessages.isEmpty()) {
            return 0;
        }
        final long budgetChars =
                (long) summarizeProperties.tokenThreshold() * summarizeProperties.charsPerToken();
        long chars = 0;
        long overflowPosition = 0;
        boolean overflowed = false;
        for (int i = allLive.size() - 1; i >= 0; i--) {
            chars += messageChars(allLive.get(i));
            if (chars > budgetChars) {
                overflowPosition = allLive.get(i).getPosition();
                overflowed = true;
                break;
            }
        }
        if (!overflowed) {
            return 0;
        }
        int cutoff = 0;
        while (cutoff < liveMessages.size()
                && liveMessages.get(cutoff).getPosition() <= overflowPosition) {
            cutoff++;
        }
        return Math.min(cutoff, liveMessages.size() - 1);
    }

    /**
     * Index of the first message of the tail that holds {@code keepUserMessages} USER messages —
     * i.e. the index of the N-th USER message counted from the end. {@code 0} means "compress
     * nothing", and it deliberately covers two cases at once: the N-th USER message really is the
     * very first row of the window, and the window holds fewer than N USER messages. Both say the
     * same thing to the caller — this rule cannot free anything — and {@code doSummarize} decides
     * whether to honour that or fall back to the count boundary.
     */
    private static int userMessageCutoff(
            List<ChatMessageEntity> liveMessages, int keepUserMessages) {
        if (keepUserMessages <= 0) {
            return liveMessages.size();
        }
        int seen = 0;
        for (int i = liveMessages.size() - 1; i >= 0; i--) {
            if (liveMessages.get(i).getType() == MessageType.USER && ++seen == keepUserMessages) {
                return i;
            }
        }
        return 0;
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

        private static MessageMix of(List<ChatMessageEntity> messages) {
            int user = 0;
            int assistant = 0;
            int tool = 0;
            int other = 0;
            int toolCalls = 0;
            for (ChatMessageEntity message : messages) {
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
            return new MessageMix(messages.size(), user, assistant, tool, other, toolCalls);
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
    private int estimateTokens(List<ChatMessageEntity> messages, long beforePosition) {
        return messages.stream()
                        .filter(m -> m.getPosition() < beforePosition)
                        .mapToInt(SummarizeService::messageChars)
                        .sum()
                / summarizeProperties.charsPerToken();
    }

    private static int messageChars(ChatMessageEntity message) {
        int chars = message.getText() == null ? 0 : message.getText().length();
        final ToolData toolData = message.getToolData();
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
            List<ChatMessageEntity> toCompress,
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
                m -> {
                    prompt.append("[msg:")
                            .append(m.getPosition())
                            .append("] ")
                            .append(m.getMessageType())
                            .append(": <msg>\n")
                            .append(m.getText())
                            // Опись приложенного дописывается к вопросу при чтении истории и в
                            // content не лежит. Без неё summarizer.md требует сохранить то, чего
                            // в его входе нет, — а вместе со сжатым сообщением исчезал бы и
                            // единственный след вложения в диалоге.
                            .append(contextItemService.render(conversationId, m.getContextItems()))
                            .append("\n</msg>\n");
                    appendToolCalls(prompt, m.getInvocations());
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
            List<ChatMessageEntity> oldMessages,
            List<ChatMessageEntity> existingSummaries,
            boolean collapseSummaries,
            String metaSummaryText,
            long endPosition) {
        if (oldMessages.isEmpty()) {
            return;
        }
        final ChatMessageEntity firstMsg =
                collapseSummaries ? existingSummaries.getFirst() : oldMessages.getFirst();
        final ChatMessageEntity lastMsg = oldMessages.getLast();

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
