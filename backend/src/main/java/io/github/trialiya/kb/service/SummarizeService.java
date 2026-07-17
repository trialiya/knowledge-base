package io.github.trialiya.kb.service;

import static io.github.trialiya.kb.utils.ChatUtils.buildContext;

import com.google.common.util.concurrent.Striped;
import io.github.trialiya.kb.config.model.SummarizeProperties;
import io.github.trialiya.kb.functions.MessageLookupFunction;
import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import io.github.trialiya.kb.repository.ChatMessageRepository;
import jakarta.annotation.Nonnull;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.Lock;
import lombok.extern.slf4j.Slf4j;
import org.apache.logging.log4j.util.Strings;
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
        // 1. Fetch all live (non-summarized) messages, excluding blanks and system msgs.
        final List<ChatMessageEntity> liveMessages =
                chatMessageRepository
                        .findChatMessageByConversationIdAndSummarizedFalseOrderByCreatedAtAscPositionAsc(
                                conversationId)
                        .stream()
                        .filter(m -> !m.isSummary())
                        .filter(m -> Strings.isNotBlank(m.getText()))
                        .toList();

        // 2. Determine the slice to compress: everything except the last overlapMessages.
        // The first KEPT message must be a USER message: assistant tool-call segments and their
        // TOOL responses form indivisible pairs, so we only compress whole turns. Otherwise a
        // kept TOOL row could lose its assistant counterpart (the model rejects orphaned tool
        // messages).
        final int overlapMessages = summarizeProperties.overlapMessages();
        int cutoff = liveMessages.size() - overlapMessages;
        while (cutoff > 0
                && cutoff < liveMessages.size()
                && liveMessages.get(cutoff).getType() != MessageType.USER) {
            cutoff--;
        }
        if (cutoff <= 0) {
            log.info(
                    "[{}] Not enough messages to compress (live={}, overlap={})",
                    conversationId,
                    liveMessages.size(),
                    overlapMessages);
            return;
        }

        // 3. Decide whether the token budget for the compressible slice is exceeded.
        final int estimatedTokens = estimateTokens(liveMessages, cutoff);
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

        final List<ChatMessageEntity> toCompress = liveMessages.subList(0, cutoff);

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
                        conversationId, existingSummaries, toCompress, cutoff, collapseSummaries);
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
        // trailing protocol rows of the last compressed turn would stay live and orphaned.
        final long endPosition =
                cutoff < liveMessages.size()
                        ? liveMessages.get(cutoff).getPosition() - 1
                        : toCompress.getLast().getPosition();
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
     * Rough token estimate for the first {@code limit} messages: total characters / charsPerToken.
     * Good enough for a threshold check; no need for a full tokenizer here.
     */
    private int estimateTokens(List<ChatMessageEntity> messages, int limit) {
        return messages.stream().limit(limit).mapToInt(m -> m.getText().length()).sum()
                / summarizeProperties.charsPerToken();
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
                m ->
                        prompt.append("[msg:")
                                .append(m.getPosition())
                                .append("] ")
                                .append(m.getMessageType())
                                .append(": <msg>\n")
                                .append(m.getText())
                                .append("\n</msg>\n"));
        if (collapseSummaries) {
            prompt.append(COLLAPSE_FOOTER);
        }

        return chatClient
                .prompt(prompt.toString())
                .toolContext(buildContext(conversationId))
                .call()
                .content();
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
