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

    private final Striped<Lock> locks = Striped.lock(1024);

    public SummarizeService(
            OpenAiChatModel openAiChatModel,
            ChatMessageRepository chatMessageRepository,
            @Value("classpath:prompt/summarizer.md") Resource summarizerPrompt,
            PlatformTransactionManager transactionManager,
            SummarizeProperties summarizeProperties) {
        this.chatClient =
                ChatClient.builder(openAiChatModel)
                        .defaultSystem(summarizerPrompt)
                        .defaultTools(new MessageLookupFunction(chatMessageRepository))
                        .build();
        this.chatMessageRepository = chatMessageRepository;
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
        // TOOL rows and tool-calls-only ASSISTANT segments — their tool_calls/response payloads
        // still occupy the model's context window on every follow-up request, so they must count
        // toward the token estimate below even though they're excluded from liveMessages/the
        // prompt.
        final List<ChatMessageEntity> allLive =
                chatMessageRepository
                        .findChatMessageByConversationIdAndSummarizedFalseOrderByCreatedAtAscPositionAsc(
                                conversationId)
                        .stream()
                        .filter(m -> !m.isSummary())
                        .toList();

        // liveMessages: the text-bearing subset used to pick the cutoff and build the LLM prompt.
        final List<ChatMessageEntity> liveMessages =
                allLive.stream().filter(m -> Strings.isNotBlank(m.getText())).toList();

        // 2. Determine the slice to compress: everything except the last overlapMessages.
        // The first KEPT message must be a USER message: assistant tool-call segments and their
        // TOOL responses form indivisible pairs, so we only compress whole turns. Otherwise a
        // kept TOOL row could lose its assistant counterpart (the model rejects orphaned tool
        // messages).
        final int overlapMessages = summarizeProperties.overlapMessages();
        final int rawCutoff = liveMessages.size() - overlapMessages;
        int cutoff = rawCutoff;
        while (cutoff > 0
                && cutoff < liveMessages.size()
                && liveMessages.get(cutoff).getType() != MessageType.USER) {
            cutoff--;
        }
        if (cutoff <= 0 && rawCutoff > 0) {
            // В окне нет ни одного USER (один вопрос → длинный tool-марафон): без запасного
            // варианта сжатие не запустилось бы никогда, и контекст рос бы неограниченно.
            // Резать по исходному cutoff безопасно: первым ОСТАВЛЕННЫМ окажется текстовый
            // ASSISTANT-сегмент (протокольные TOOL-строки пусты и в liveMessages не входят),
            // а пометка summarized идёт по позициям — парные TOOL-строки уходят в summary
            // вместе со своими assistant-сегментами, ответ на tool_calls сегмента-границы
            // остаётся живым вместе с ним.
            cutoff = rawCutoff;
        }
        if (cutoff <= 0) {
            log.info(
                    "[{}] Not enough messages to compress (live={}, overlap={})",
                    conversationId,
                    liveMessages.size(),
                    overlapMessages);
            return;
        }

        // Position boundary of the compressible slice: everything with position < cutoffPosition
        // (in allLive, so including interleaved TOOL rows/blank tool-call segments) belongs to
        // this round. Reused both for the token estimate and, below, for marking rows summarized.
        final long cutoffPosition =
                cutoff < liveMessages.size()
                        ? liveMessages.get(cutoff).getPosition()
                        : Long.MAX_VALUE;

        // 3. Decide whether the token budget for the compressible slice is exceeded. Counts text
        // AND tool call arguments / tool response payloads — both are sent to the model as context
        // on every follow-up request, so a large tool result must weigh into the decision just like
        // a large assistant reply would.
        final int estimatedTokens = estimateTokens(allLive, cutoffPosition);
        final int messageCountThreshold = summarizeProperties.messageCountThreshold();
        final int tokenThreshold = summarizeProperties.tokenThreshold();
        if (cutoff < messageCountThreshold && estimatedTokens < tokenThreshold) {
            log.info(
                    "[{}] Skipping summarization. Messages: {} < threshold: {}. Estimated tokens: {} < threshold: {}",
                    conversationId,
                    cutoff,
                    messageCountThreshold,
                    estimatedTokens,
                    tokenThreshold);
            return;
        }

        // toCompress is built from allLive (not liveMessages) so that tool-calls-only ASSISTANT
        // segments (blank text, but carrying invocations) aren't silently dropped from the
        // summarizer prompt — liveMessages only exists to pick the cutoff/position boundary above.
        // Blank TOOL protocol rows with no invocations are still excluded: they duplicate
        // information already exposed via the owning ASSISTANT segment's invocations.
        final List<ChatMessageEntity> toCompress =
                allLive.stream()
                        .filter(m -> m.getPosition() < cutoffPosition)
                        .filter(
                                m ->
                                        Strings.isNotBlank(m.getText())
                                                || (m.getInvocations() != null
                                                        && !m.getInvocations().isEmpty()))
                        .toList();

        log.info(
                "[{}] Compressing: {} - {}",
                conversationId,
                toCompress.getFirst().getPosition(),
                toCompress.getLast().getPosition());

        // 4. Load existing summaries to give the LLM prior context.
        final List<ChatMessageEntity> existingSummaries =
                chatMessageRepository
                        .findChatMessageByConversationIdAndSummarizedFalseAndSummaryTrueOrderByCreatedAtAscPositionAsc(
                                conversationId);

        // 5. Generate summary text via LLM. Collapse existing summaries into one meta-summary
        // if this round's new summary would otherwise push the count to summaryCollapseThreshold.
        final boolean collapseSummaries =
                existingSummaries.size() + 1 >= summarizeProperties.summaryCollapseThreshold();
        final String summaryContent =
                generateSummary(
                        conversationId,
                        existingSummaries,
                        toCompress,
                        toCompress.size(),
                        collapseSummaries);
        if (Strings.isBlank(summaryContent)) {
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
                "[{}] Summarization finished ({} messages ({} tokens) compressed) -> {} tokens",
                conversationId,
                cutoff,
                estimatedTokens,
                summaryText.length() / summarizeProperties.charsPerToken());

        // 7. Persist: mark compressed messages as summarized, insert new summary row.
        // liveMessages excludes blank rows (TOOL responses, empty tool-call segments), so the
        // marked range must run up to (but not including) the first KEPT message — otherwise the
        // trailing protocol rows of the last compressed turn would stay live and orphaned. When
        // everything is compressed (no kept message), the range must cover trailing protocol rows
        // too — allLive, not toCompress, holds the true last position.
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

    private String generateSummary(
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
