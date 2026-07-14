package io.github.trialiya.kb.service;

import io.github.trialiya.kb.model.doc.dto.DocumentNode;
import io.github.trialiya.kb.model.doc.entity.DocumentEntity;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Generates and persists AI summaries for document descriptions.
 *
 * <h3>Stale semantics</h3>
 *
 * Each document tracks a {@code descriptionVersion} counter that is incremented <em>only</em> when
 * the {@code description} column changes (not on rename / move / reorder). The summary is
 * considered stale when {@code summarySourceVersion < descriptionVersion}. This service always
 * writes {@code summarySourceVersion = entity.descriptionVersion} after a successful generation,
 * clearing the stale flag until the description changes again.
 *
 * <h3>Content policy</h3>
 *
 * Unlike {@link AttachmentService}, the description is never truncated before being sent to the LLM
 * — document descriptions are short markdown texts, not uploaded files.
 */
@Slf4j
@Service
public class DocumentSummaryService {

    // Intentionally not shared with AttachmentService: the two prompts serve
    // different content shapes (structured markdown vs raw file content).
    private static final String SUMMARIZE_PROMPT =
            """
        Создай краткое описание документа с заголовком "{title}".
        Описание должно передавать основную тему, ключевые понятия и структуру содержимого. \
        2-5 предложения. Используй простой текст без форматирования.

        Содержимое документа (Markdown):
        ```
        {description}
        ```
        """;

    private final ChatClient chatClient;

    public DocumentSummaryService(OpenAiChatModel openAiChatModel) {
        this.chatClient = ChatClient.builder(openAiChatModel).build();
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Generates an AI summary for the document's description and persists it.
     *
     * <p>The description is sent to the LLM in full — no truncation. {@code summarySourceVersion}
     * is set to the current {@code descriptionVersion}, clearing the stale flag until the
     * description changes again.
     *
     * @param entity document
     * @return updated {@link DocumentNode} with summary fields populated
     * @throws ResponseStatusException 404 if the document does not exist
     * @throws ResponseStatusException 422 if the document has no description to summarise
     * @throws ResponseStatusException 409 on optimistic lock conflict
     */
    @Nullable
    public String summarize(DocumentEntity entity) {
        String summaryText =
                chatClient
                        .prompt()
                        .user(
                                u ->
                                        u.text(SUMMARIZE_PROMPT)
                                                .param("title", entity.getTitle())
                                                .param("description", entity.getDescription()))
                        .call()
                        .content();
        log.info("Summarised document id={} title='{}'", entity.getId(), entity.getTitle());
        return summaryText != null ? summaryText.trim() : null;
    }
}
