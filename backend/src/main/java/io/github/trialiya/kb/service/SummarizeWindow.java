package io.github.trialiya.kb.service;

import io.github.trialiya.kb.config.model.SummarizeProperties;
import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import io.github.trialiya.kb.model.tool.ToolData;
import io.github.trialiya.kb.service.ChatMemoryService.PromptRow;
import java.util.List;
import java.util.OptionalInt;
import java.util.stream.Stream;
import org.apache.logging.log4j.util.Strings;
import org.springframework.ai.chat.messages.MessageType;

/**
 * The live window of a conversation and the boundary of its compressible slice — all of {@code
 * SummarizeService}'s arithmetic with no side effects: rows of history as the model receives them
 * in ({@link ChatMemoryService#promptRows}), "what to compress, what to keep and why" out.
 *
 * <p>Four rules shape the boundary and they are not peers. Three are PREFERENCES about what the
 * live tail should hold, and each is only an upper bound on how much may be compressed: at least
 * {@code overlap-messages} messages of any kind, at least {@code overlap-user-messages} USER
 * messages, and the tail should open on a whole turn. A preference that cannot be satisfied simply
 * does not apply — it never forces the boundary anywhere, which is why none of them needs a
 * fallback branch to rescue it. One rule is a FLOOR, and it is the only one that bounds anything:
 * whatever the tail is, it must fit the {@code token-threshold} budget. The cutoff is {@code
 * max(floor, preferred)}.
 *
 * <p>Two views of the window matter and they differ exactly by the empty TOOL protocol rows: {@link
 * #allLive()} keeps them because their payloads occupy the model's context on every request, so
 * they weigh on the budget; {@link #prompt()} drops them because the summarizer prompt has nothing
 * to quote from a blank row — their info is already exposed via the owning ASSISTANT segment's
 * invocations. The boundary is picked over {@link #prompt()}, the budget is measured over {@link
 * #allLive()}, and marking runs by positions so the two never disagree about tool pairs.
 */
final class SummarizeWindow {

    /**
     * Flat per-message protocol overhead charged by {@code messageChars}, in characters — roughly
     * four tokens at the default {@code chars-per-token}, which is about what a role plus the JSON
     * envelope around one message costs in an OpenAI-shaped request. Deliberately not a configured
     * property: it describes the wire format, not a preference anyone should be tuning.
     */
    private static final int PER_MESSAGE_CHARS = 16;

    private final SummarizeProperties properties;
    private final List<ChatMessageEntity> summaries;
    private final List<PromptRow> allLive;
    private final List<PromptRow> prompt;
    private final long budgetChars;
    private final int preferred;
    private final int floor;
    private final int cutoff;

    SummarizeWindow(List<PromptRow> rows, SummarizeProperties properties) {
        this.properties = properties;
        // Summaries ride along in every request but are not part of the window this round may
        // compress — they are already-compressed past. So they leave the window and take their
        // share of the budget with them: the window's real allowance is what they leave behind.
        this.summaries =
                rows.stream().map(PromptRow::entity).filter(ChatMessageEntity::isSummary).toList();
        this.allLive = rows.stream().filter(row -> !row.entity().isSummary()).toList();
        this.prompt = allLive.stream().filter(SummarizeWindow::saysAnything).toList();

        int preferred = prompt.size() - properties.overlapMessages();
        preferred =
                Math.min(
                        preferred,
                        userBoundary(prompt, properties.overlapUserMessages()).orElse(preferred));
        this.preferred = turnBoundary(prompt, preferred).orElse(preferred);

        this.budgetChars =
                (long) properties.tokenThreshold() * properties.charsPerToken()
                        - charsOf(rows.stream().filter(row -> row.entity().isSummary()));
        this.floor = tokenBudgetCutoff(allLive, prompt, budgetChars);
        this.cutoff = Math.max(floor, this.preferred);
    }

    /**
     * Prompt-eligible: a row with something to tell the summarizer — non-blank prompt text, or a
     * tool-calls-only ASSISTANT segment (blank text but non-empty invocations). Judged on {@link
     * PromptRow#text()} — the same text the summarizer prompt sends — not on the stored column: a
     * second way to ask "does this row say anything" is exactly the split {@code PromptRow} exists
     * to prevent.
     */
    private static boolean saysAnything(PromptRow row) {
        return Strings.isNotBlank(row.text())
                || (row.entity().getInvocations() != null
                        && !row.entity().getInvocations().isEmpty());
    }

    // -------------------------------------------------------------------------
    // What the service asks
    // -------------------------------------------------------------------------

    /** The live window including empty TOOL protocol rows — what the budget weighs. */
    List<PromptRow> allLive() {
        return allLive;
    }

    /** The prompt-eligible subset — what the boundary is picked over and the summarizer sees. */
    List<PromptRow> prompt() {
        return prompt;
    }

    /** Existing summary rows, oldest first — context for the next summary, collapsible past. */
    List<ChatMessageEntity> summaries() {
        return summaries;
    }

    /**
     * Whether this round should run at all. Each threshold measures the thing its own name is
     * about: {@code message-count-threshold} the slice that would be compressed, {@code
     * token-threshold} the whole live window. Asking the budget question through the floor keeps
     * the trigger and the boundary from disagreeing at the edge: over budget the floor hands the
     * round real work, under it there is nothing to free — so the only reason left to run is a
     * slice that grew big enough by count.
     */
    boolean notWorthARound() {
        return cutoff <= 0 || (floor == 0 && cutoff < properties.messageCountThreshold());
    }

    /** True when the budget pushed the boundary past every preference — worth a log line. */
    boolean budgetForcedTheBoundary() {
        return floor > preferred;
    }

    int preferred() {
        return preferred;
    }

    int floor() {
        return floor;
    }

    List<PromptRow> toCompress() {
        return prompt.subList(0, Math.max(cutoff, 0));
    }

    List<PromptRow> kept() {
        return prompt.subList(cutoff, prompt.size());
    }

    /**
     * The last position this round marks summarized. {@code prompt} excludes empty TOOL protocol
     * rows, so the range must run up to (but not including) the first KEPT message — otherwise the
     * trailing protocol rows of the last compressed turn would stay live and orphaned. When
     * everything is compressed (no kept message), the range must cover those trailing rows too, and
     * only {@code allLive} — not {@code toCompress} — holds the true last position.
     */
    long endPosition() {
        final long cutoffPosition = cutoffPosition();
        return cutoffPosition == Long.MAX_VALUE
                ? allLive.getLast().entity().getPosition()
                : cutoffPosition - 1;
    }

    /** Estimated tokens of the compressible slice, empty TOOL protocol rows included. */
    int sliceTokens() {
        final long cutoffPosition = cutoffPosition();
        return tokens(
                charsOf(
                        allLive.stream()
                                .filter(row -> row.entity().getPosition() < cutoffPosition)));
    }

    /** Estimated tokens of the whole live window — what every follow-up request sends. */
    int windowTokens() {
        return tokens(charsOf(allLive.stream()));
    }

    /** The window's share of the token budget: the configured budget minus the summaries' take. */
    int budgetTokens() {
        return tokens(budgetChars);
    }

    /**
     * Position boundary of the compressible slice: everything positioned before it (in {@code
     * allLive}, so including interleaved TOOL protocol rows) belongs to this round.
     */
    private long cutoffPosition() {
        return cutoff < prompt.size() ? prompt.get(cutoff).entity().getPosition() : Long.MAX_VALUE;
    }

    private int tokens(long chars) {
        return (int) (chars / properties.charsPerToken());
    }

    // -------------------------------------------------------------------------
    // The four boundary rules
    // -------------------------------------------------------------------------

    /**
     * The floor under the cutoff: the earliest boundary whose KEPT tail still fits the budget.
     * Walks back from the newest row adding up characters until the budget is spent — everything at
     * or before the row that overflows it has to be compressed. Returns {@code 0} while the whole
     * window fits, which is also the answer to "is this conversation over budget at all", and
     * {@link #notWorthARound} uses it as exactly that.
     *
     * <p>This is the only rule that bounds the live window. The preferences can move the boundary
     * earlier and never later, so on their own they let the window grow to whatever the last few
     * turns happen to produce: five questions answered by a thousand tool rows would keep all
     * thousand rows live, the compressible slice a couple of messages wide, and no round would ever
     * run.
     *
     * <p>The result is deliberately not aligned onto a USER message the way {@link #turnBoundary}
     * asks for: the floor exists for windows where the nearest USER row is far behind, and aligning
     * would hand the budget straight back. Nothing breaks — the tool-pair invariant does not come
     * from alignment at all, see {@link #endPosition}. The newest message always stays live:
     * summarizing the question that was just asked would buy nothing.
     *
     * @param budgetChars what the window may spend, i.e. the configured budget minus what the
     *     summaries already take. Non-positive means the summaries alone are over budget, and every
     *     row overflows at once — the window is squeezed to its newest message, which is the most
     *     this rule can do.
     */
    private static int tokenBudgetCutoff(
            List<PromptRow> allLive, List<PromptRow> prompt, long budgetChars) {
        if (prompt.isEmpty()) {
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
        while (cutoff < prompt.size()
                && prompt.get(cutoff).entity().getPosition() <= overflowPosition) {
            cutoff++;
        }
        return Math.min(cutoff, prompt.size() - 1);
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
     * Preference: the tail should open on a whole turn, so the boundary is walked back from {@code
     * upperBound} to the nearest USER message. Empty when there is none to walk back to, and when
     * the bound already covers the whole window (nothing is being kept, so nothing needs opening).
     *
     * <p>This is taste, not protocol. It is tempting to read it as the rule that keeps an ASSISTANT
     * tool-call segment together with its TOOL responses, and the code used to say so — but that
     * invariant is held by position-based marking instead (see {@link #endPosition}), and holds
     * with or without this preference. Treating it as load-bearing is what once made an
     * unsatisfiable alignment collapse the boundary to zero and need a fallback branch to rescue
     * it.
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

    /**
     * How many messages a slice holds and of what kind — what the log needs to answer "how much
     * context is this, and whose". {@code toolCalls} counts the individual tool invocations carried
     * by the ASSISTANT segments, not the segments themselves: a single segment can fire several
     * tools, and it is the invocations that fill the context window. {@code other} catches the
     * types that are neither of the three (a live SYSTEM row, say) so the breakdown always adds up
     * to {@code total} — a log line whose numbers silently don't sum is worse than no log line.
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
