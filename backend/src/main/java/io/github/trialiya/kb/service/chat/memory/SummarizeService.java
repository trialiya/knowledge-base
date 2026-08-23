package io.github.trialiya.kb.service.chat.memory;

import static io.github.trialiya.kb.utils.ChatUtils.context;

import com.google.common.util.concurrent.Striped;
import io.github.trialiya.kb.config.model.SummarizeProperties;
import io.github.trialiya.kb.functions.MessageLookupFunction;
import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import io.github.trialiya.kb.model.chat.entity.ChatMessageMeta;
import io.github.trialiya.kb.model.tool.ToolInvocationMeta;
import io.github.trialiya.kb.repository.ChatMessageRepository;
import io.github.trialiya.kb.service.chat.context.ContextItemService;
import io.github.trialiya.kb.service.chat.memory.ChatHistoryService.PromptRow;
import io.github.trialiya.kb.service.chat.memory.SummarizeWindow.MessageMix;
import jakarta.annotation.Nonnull;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.Lock;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Background compression of long conversations. This class is the orchestration only — one round
 * per conversation at a time, the LLM call, the atomic persist; everything about WHERE the boundary
 * goes lives in {@link SummarizeWindow}.
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
        and the following new messages. The result must be a cohesive summary of the entire conversation so far.""";

    private final ChatClient chatClient;
    private final ChatMessageRepository chatMessageRepository;
    private final ChatHistoryService chatHistory;
    private final ExecutorService executorService;
    private final TransactionTemplate transactionTemplate;
    private final SummarizeProperties summarizeProperties;

    private final Striped<Lock> locks = Striped.lock(1024);

    public SummarizeService(
            OpenAiChatModel openAiChatModel,
            ChatMessageRepository chatMessageRepository,
            ChatHistoryService chatHistory,
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
        this.chatHistory = chatHistory;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.executorService = Executors.newVirtualThreadPerTaskExecutor();
        this.summarizeProperties = summarizeProperties;
    }

    @Override
    public void destroy() {
        executorService.shutdown();
    }

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

    public void doSummarize(@Nonnull final String conversationId) {
        // promptRows is the one place that answers "what does the model see": every row carries the
        // text that will be sent, inventory included, not the text that happens to be stored. The
        // prompt below and the estimate inside SummarizeWindow both measure exactly that.
        final SummarizeWindow window =
                new SummarizeWindow(chatHistory.promptRows(conversationId), summarizeProperties);

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
                    "[{}] Skipping summarization — compressible: {}, ~{} tokens; neither threshold"
                            + " reached ({} messages / {} tokens). Live window: ~{} tokens",
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
                "[{}] Compressing positions {}-{} ({} reached the threshold): {}, ~{} tokens;"
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
        final boolean collapseSummaries =
                existingSummaries.size() + 1 >= summarizeProperties.summaryCollapseThreshold();
        final @Nullable String summaryContent =
                generateSummary(conversationId, existingSummaries, toCompress, collapseSummaries);
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

        log.info(
                "[{}] Summarization finished — compressed {} (~{} tokens) into ~{} tokens",
                conversationId,
                MessageMix.of(toCompress),
                window.sliceTokens(),
                summaryText.length() / summarizeProperties.charsPerToken());

        persistSummary(
                conversationId,
                toCompress,
                existingSummaries,
                collapseSummaries,
                summaryText,
                window.endPosition());
    }

    // -------------------------------------------------------------------------
    // The LLM round
    // -------------------------------------------------------------------------

    private @Nullable String generateSummary(
            String conversationId,
            List<ChatMessageEntity> existingSummaries,
            List<PromptRow> toCompress,
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
        final @Nullable String project = lastProject(oldMessages, existingSummaries);

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
                                    project == null ? null : ChatMessageMeta.ofProject(project)));
                });
    }

    /**
     * On which project the compressed slice ended — carried onto the summary row so the trace of a
     * project switch survives its own marker being summarized away. {@code null} when the slice
     * never switched (the chat's own project covers it).
     */
    private static @Nullable String lastProject(
            List<PromptRow> oldMessages, List<ChatMessageEntity> existingSummaries) {
        return Stream.concat(
                        existingSummaries.stream(), oldMessages.stream().map(PromptRow::entity))
                .map(ChatMessageEntity::getMeta)
                .filter(meta -> meta != null && meta.project() != null)
                .reduce((first, second) -> second)
                .map(ChatMessageMeta::project)
                .orElse(null);
    }
}
