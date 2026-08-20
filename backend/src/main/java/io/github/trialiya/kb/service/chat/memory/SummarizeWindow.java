package io.github.trialiya.kb.service.chat.memory;

import io.github.trialiya.kb.config.model.SummarizeProperties;
import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import io.github.trialiya.kb.model.tool.ToolData;
import io.github.trialiya.kb.service.chat.memory.ChatMemoryService.PromptRow;
import java.util.List;
import java.util.OptionalInt;
import java.util.stream.Stream;
import org.apache.logging.log4j.util.Strings;
import org.springframework.ai.chat.messages.MessageType;

/**
 * The live window of a conversation cut in two — all of {@code SummarizeService}'s arithmetic, with
 * no side effects. In: history as the model receives it ({@link ChatMemoryService#promptRows}).
 * Out: what to compress, what to keep, and whether it is worth a round.
 *
 * <p><b>The live tail</b> ({@link #kept()}) is untouchable, and three rules say how far back it
 * reaches — the earliest wins: at least {@code overlap-user-messages} USER messages, at least
 * {@code overlap-messages} messages of any kind, and it opens on a whole turn. A rule that cannot
 * be satisfied stands aside instead of forcing a boundary, so none of them needs a fallback branch.
 *
 * <p><b>Everything older</b> ({@link #toCompress()}) is the slice, compressed whole or not at all.
 * Either threshold starts a round, and both measure the slice: {@code message-count-threshold} by
 * count, {@code token-threshold} by weight.
 *
 * <p><b>Summaries take no part in this.</b> They are already-compressed past: handed to the
 * summarizer as context ({@link #summaries()}), never compressed again, never counted.
 *
 * <p>Deliberately not promised: a bound on the live tail. Every rule only moves the boundary
 * earlier, so a tool marathon inside the last few turns stays live until later questions push it
 * out. The window drains as the conversation moves on rather than being clamped — the price of a
 * boundary a reader can predict without simulating it.
 */
final class SummarizeWindow {

    /**
     * Flat per-message protocol overhead in characters — the role plus the JSON envelope around one
     * message, about four tokens at the default {@code chars-per-token}. Not a property: it
     * describes the wire format, not a preference. Without it an empty TOOL row weighs nothing and
     * a slice of a thousand short rows estimates as nearly free.
     */
    private static final int PER_MESSAGE_CHARS = 16;

    private final SummarizeProperties properties;
    private final List<ChatMessageEntity> summaries;
    private final List<PromptRow> allLive;
    private final List<PromptRow> prompt;
    private final int cutoff;
    private final long cutoffPosition;
    private final int sliceTokens;

    SummarizeWindow(List<PromptRow> rows, SummarizeProperties properties) {
        this.properties = properties;
        this.summaries =
                rows.stream().map(PromptRow::entity).filter(ChatMessageEntity::isSummary).toList();
        // allLive keeps the blank-text TOOL protocol rows — their payloads occupy the model's
        // context on every request, so they weigh on the slice. prompt drops them: a blank row
        // gives the summarizer nothing to quote, and its content is already exposed through the
        // owning ASSISTANT segment.
        this.allLive = rows.stream().filter(row -> !row.entity().isSummary()).toList();
        this.prompt = allLive.stream().filter(SummarizeWindow::saysAnything).toList();

        this.cutoff = Math.max(0, tailStart(prompt, properties));
        this.cutoffPosition =
                cutoff < prompt.size() ? prompt.get(cutoff).entity().getPosition() : Long.MAX_VALUE;
        this.sliceTokens =
                tokens(
                        charsOf(
                                allLive.stream()
                                        .filter(
                                                row ->
                                                        row.entity().getPosition()
                                                                < cutoffPosition)));
    }

    /**
     * Where the live tail begins — the earliest boundary the three rules allow. Each is only an
     * upper bound on how much may be compressed, so the minimum wins.
     */
    private static int tailStart(List<PromptRow> prompt, SummarizeProperties properties) {
        int start = prompt.size() - properties.overlapMessages();
        start =
                Math.min(
                        start,
                        userBoundary(prompt, properties.overlapUserMessages()).orElse(start));
        return turnBoundary(prompt, start).orElse(start);
    }

    /**
     * Prompt-eligible: a row with something to tell the summarizer — non-blank prompt text, or a
     * tool-calls-only ASSISTANT segment. Judged on {@link PromptRow#text()}, the text the prompt
     * actually sends, not on the stored column — a second way to ask "does this row say anything"
     * is exactly the split {@code PromptRow} exists to prevent.
     */
    private static boolean saysAnything(PromptRow row) {
        return Strings.isNotBlank(row.text())
                || (row.entity().getInvocations() != null
                        && !row.entity().getInvocations().isEmpty());
    }

    // -------------------------------------------------------------------------
    // What the service asks
    // -------------------------------------------------------------------------

    /** The live window including empty TOOL protocol rows — what the estimate weighs. */
    List<PromptRow> allLive() {
        return allLive;
    }

    /** The prompt-eligible subset — what the boundary is picked over and the summarizer sees. */
    List<PromptRow> prompt() {
        return prompt;
    }

    /**
     * Existing summaries, oldest first — context for the next summary and the collapsible past.
     * Neither compressed again nor counted against either threshold.
     */
    List<ChatMessageEntity> summaries() {
        return summaries;
    }

    /** The slice this round would compress: everything older than the live tail. */
    List<PromptRow> toCompress() {
        return prompt.subList(0, cutoff);
    }

    /** The live tail, kept whole. */
    List<PromptRow> kept() {
        return prompt.subList(cutoff, prompt.size());
    }

    /** Whether the slice has grown enough to be worth a round — either threshold is enough. */
    boolean worthARound() {
        return cutoff > 0
                && (cutoff >= properties.messageCountThreshold()
                        || sliceTokens >= properties.tokenThreshold());
    }

    /** Which threshold called this round — for the log line that explains it. */
    String trigger() {
        return cutoff >= properties.messageCountThreshold() ? "message count" : "token estimate";
    }

    /**
     * The last position this round marks summarized: everything up to, but not including, the first
     * KEPT message. {@link #prompt} excludes empty TOOL protocol rows, so a range ending at the
     * last compressed row would leave the trailing protocol rows of that turn live and orphaned.
     * When nothing is kept, only {@link #allLive} holds the true last position.
     *
     * <p>This, and not the turn alignment below, is what keeps an ASSISTANT tool-call segment
     * together with its TOOL responses — whichever rule picked the boundary.
     */
    long endPosition() {
        return cutoffPosition == Long.MAX_VALUE
                ? allLive.getLast().entity().getPosition()
                : cutoffPosition - 1;
    }

    /** Estimated tokens of the slice, empty TOOL protocol rows included. */
    int sliceTokens() {
        return sliceTokens;
    }

    /** Estimated tokens of the whole live window — what every follow-up request sends. */
    int windowTokens() {
        return tokens(charsOf(allLive.stream()));
    }

    private int tokens(long chars) {
        return (int) (chars / properties.charsPerToken());
    }

    // -------------------------------------------------------------------------
    // The three tail rules
    // -------------------------------------------------------------------------

    /**
     * The tail should hold {@code keepUserMessages} USER messages, so the boundary may not go past
     * the N-th USER message counted from the end. Empty when the window does not hold that many —
     * the rule stands aside rather than forcing a boundary it cannot justify.
     *
     * <p>Index {@code 0} is a real answer here, not a failure: the N-th question from the end opens
     * the window, so nothing older than the tail exists and this round has no work.
     */
    private static OptionalInt userBoundary(List<PromptRow> prompt, int keepUserMessages) {
        if (keepUserMessages <= 0) {
            return OptionalInt.empty();
        }
        int seen = 0;
        for (int i = prompt.size() - 1; i >= 0; i--) {
            if (prompt.get(i).entity().getType() == MessageType.USER
                    && ++seen == keepUserMessages) {
                return OptionalInt.of(i);
            }
        }
        return OptionalInt.empty();
    }

    /**
     * The tail should open on a whole turn, so the boundary is walked back from {@code upperBound}
     * to the nearest USER message. Empty when the bound already covers the whole window (nothing is
     * kept, so nothing needs opening) or when there is no USER message to walk back to.
     *
     * <p>Unlike {@link #userBoundary}, index {@code 0} is <em>not</em> an answer here: the search
     * stops short of it, so a window whose only question is its very first message yields empty and
     * the boundary stays where the count rule put it. That is the one case where the tail may open
     * mid-turn, and it is the intended trade — the alternative is a single question followed by an
     * unbounded tool marathon that can never be compressed at all.
     *
     * <p>This is about what the model reads, not about protocol integrity: a tail that starts
     * mid-turn opens on an answer to a question the model can no longer see. Tool-call pairing is
     * held by position-based marking instead — see {@link #endPosition}.
     */
    private static OptionalInt turnBoundary(List<PromptRow> prompt, int upperBound) {
        if (upperBound <= 0 || upperBound >= prompt.size()) {
            return OptionalInt.empty();
        }
        for (int i = upperBound; i > 0; i--) {
            if (prompt.get(i).entity().getType() == MessageType.USER) {
                return OptionalInt.of(i);
            }
        }
        return OptionalInt.empty();
    }

    // -------------------------------------------------------------------------
    // Weighing
    // -------------------------------------------------------------------------

    private static long charsOf(Stream<PromptRow> rows) {
        return rows.mapToLong(SummarizeWindow::messageChars).sum();
    }

    /**
     * What one message costs the request, in characters — measured on {@link PromptRow#text()}, the
     * text that will be sent, not on {@code chat_message.content}. The two differ by the attachment
     * inventory, rendered at read time and stored nowhere; counting the stored column would leave
     * the whole inventory outside the estimate, and an attachment summary has no length limit.
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

    /**
     * How many messages a slice holds and of what kind — what the log needs to answer "how much
     * context is this, and whose". {@code toolCalls} counts individual invocations, not the
     * ASSISTANT segments carrying them: one segment can fire several tools, and it is the
     * invocations that fill the context window. {@code other} catches the remaining types (a live
     * SYSTEM row, say) so the breakdown always adds up to {@code total}.
     */
    record MessageMix(int total, int user, int assistant, int tool, int other, int toolCalls) {

        static MessageMix of(List<PromptRow> rows) {
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
}
