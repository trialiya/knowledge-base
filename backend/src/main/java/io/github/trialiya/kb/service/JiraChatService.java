package io.github.trialiya.kb.service;

import static io.github.trialiya.kb.utils.ChatUtils.getUser;

import io.github.trialiya.kb.model.attachment.entity.AttachmentEntity;
import io.github.trialiya.kb.model.chat.dto.Chat;
import io.github.trialiya.kb.model.chat.dto.CreateJiraChatRequest;
import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import io.github.trialiya.kb.model.chat.entity.ChatTopicEntity;
import io.github.trialiya.kb.repository.AttachmentRepository;
import io.github.trialiya.kb.repository.ChatMessageRepository;
import io.github.trialiya.kb.repository.ChatTopicRepository;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Orchestrates the creation of a "JIRA chat" — a chat conversation pre-loaded with context from a
 * JIRA issue and optionally a Confluence page.
 *
 * <p>Flow:
 *
 * <ol>
 *   <li>Parse the JIRA URL → issue key
 *   <li>Fetch issue content via {@link JiraService}
 *   <li>Optionally fetch Confluence page via {@link ConfluenceService}
 *   <li>Create a {@link ChatTopicEntity} with the issue key as title (or custom title)
 *   <li>Store fetched content as attachments with {@code source_url}
 *   <li>Trigger summarization for each attachment
 *   <li>Save an initial ASSISTANT message summarising the task context
 * </ol>
 */
@Slf4j
@Service
@AllArgsConstructor
public class JiraChatService {

    private final JiraService jiraService;
    private final ConfluenceService confluenceService;
    private final ChatTopicRepository chatTopicRepository;
    private final AttachmentRepository attachmentRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final AttachmentService attachmentService;

    @Transactional
    public Chat createJiraChat(CreateJiraChatRequest request) {
        if (request.jiraUrl() == null || request.jiraUrl().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "jiraUrl is required");
        }

        // 1. Fetch JIRA issue
        String issueKey = jiraService.parseIssueKeyFromUrl(request.jiraUrl());
        JiraService.JiraIssue issue = jiraService.fetchIssue(issueKey);

        // 2. Create chat topic
        String conversationId = UUID.randomUUID().toString();
        String title =
                (request.title() != null && !request.title().isBlank())
                        ? request.title()
                        : issueKey + ": " + truncate(issue.summary(), 60);

        ChatTopicEntity chatTopicEntity =
                new ChatTopicEntity(conversationId, getUser(), true, title, null, true);
        chatTopicRepository.save(chatTopicEntity);

        // 3. Store JIRA content as attachment
        AttachmentEntity jiraAttachment =
                createAttachment(
                        conversationId, issueKey + ".md", issue.content(), request.jiraUrl());
        attachmentRepository.save(jiraAttachment);

        // 4. Optionally fetch and store Confluence page
        String confluenceFileName = null;
        if (request.confluenceUrl() != null && !request.confluenceUrl().isBlank()) {
            try {
                String pageId = confluenceService.parsePageIdFromUrl(request.confluenceUrl());
                ConfluenceService.ConfluencePage page = confluenceService.fetchPage(pageId);

                String fileName = "confluence-" + pageId + ".md";
                AttachmentEntity confluenceAttachment =
                        createAttachment(
                                conversationId, fileName, page.content(), request.confluenceUrl());
                attachmentRepository.save(confluenceAttachment);
                confluenceFileName = fileName;

                log.info(
                        "Created Confluence attachment for page '{}' in chat {}",
                        page.title(),
                        conversationId);
            } catch (Exception e) {
                log.warn(
                        "Failed to fetch Confluence page from {}: {}",
                        request.confluenceUrl(),
                        e.getMessage());
            }
        }

        // 5. Trigger summarization for all attachments in this chat
        try {
            attachmentRepository
                    .findByConversationId(conversationId)
                    .forEach(
                            att -> {
                                try {
                                    attachmentService.summarize(att.getId());
                                } catch (Exception e) {
                                    log.warn(
                                            "Summarization failed for attachment {}: {}",
                                            att.getId(),
                                            e.getMessage());
                                }
                            });
        } catch (Exception e) {
            log.warn("Summarization pass failed: {}", e.getMessage());
        }

        // 6. Save initial ASSISTANT greeting message so the user immediately
        //    sees the task context when the chat is opened.
        saveGreetingMessage(
                conversationId, issueKey, issue.summary(), issueKey + ".md", confluenceFileName);

        log.info(
                "Created JIRA chat: conversationId={}, issue={}, title={}",
                conversationId,
                issueKey,
                title);

        // Reload to get timestamps
        ChatTopicEntity saved =
                chatTopicRepository.findById(conversationId).orElse(chatTopicEntity);

        return new Chat(
                saved.getConversationId(),
                saved.getUser(),
                saved.getTopic(),
                saved.getModel(),
                saved.getCreatedAt(),
                saved.getUpdatedAt(),
                List.of());
    }

    /**
     * Re-fetches JIRA (and Confluence if present) data and replaces existing attachments. The
     * greeting message is NOT re-created — it stays as conversation history.
     */
    @Transactional
    public Chat refreshJiraChat(String conversationId, String jiraUrl) {
        ChatTopicEntity chatTopicEntity =
                chatTopicRepository
                        .findById(conversationId)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Chat not found: " + conversationId));

        if (!chatTopicEntity.getUser().equals(getUser())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Forbidden");
        }

        // Delete existing attachments (keep chat messages intact)
        attachmentRepository.deleteByConversationId(conversationId);

        String issueKey = jiraService.parseIssueKeyFromUrl(jiraUrl);
        JiraService.JiraIssue issue = jiraService.fetchIssue(issueKey);

        AttachmentEntity jiraAttachment =
                createAttachment(conversationId, issueKey + ".md", issue.content(), jiraUrl);
        attachmentRepository.save(jiraAttachment);

        // Re-summarize
        try {
            attachmentService.summarize(jiraAttachment.getId());
        } catch (Exception e) {
            log.warn("Summarization failed on refresh: {}", e.getMessage());
        }

        log.info("Refreshed JIRA chat: conversationId={}, issue={}", conversationId, issueKey);

        ChatTopicEntity saved =
                chatTopicRepository.findById(conversationId).orElse(chatTopicEntity);
        return new Chat(
                saved.getConversationId(),
                saved.getUser(),
                saved.getTopic(),
                saved.getModel(),
                saved.getCreatedAt(),
                saved.getUpdatedAt(),
                List.of());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Saves the initial ASSISTANT message that greets the user and names the active JIRA task. This
     * message is visible immediately when the chat loads — it sets context without requiring the
     * user to ask anything first.
     */
    private void saveGreetingMessage(
            String conversationId,
            String issueKey,
            String issueSummary,
            String jiraFileName,
            @Nullable String confluenceFileName) {

        StringBuilder sb = new StringBuilder();
        sb.append("Веду работу над задачей **").append(issueKey).append("**");
        if (issueSummary != null && !issueSummary.isBlank()) {
            sb.append(": ").append(truncate(issueSummary, 120));
        }
        sb.append(".\n\n");
        sb.append("Полные детали задачи загружены во вложение **")
                .append(jiraFileName)
                .append("**");
        if (confluenceFileName != null) {
            sb.append(", связанная документация — **").append(confluenceFileName).append("**");
        }
        sb.append(".\n\n");
        sb.append("Чем могу помочь?");

        chatMessageRepository.save(
                new ChatMessageEntity(
                        0L,
                        conversationId,
                        sb.toString(),
                        MessageType.ASSISTANT,
                        1L,
                        false,
                        false,
                        LocalDateTime.now(),
                        null));
    }

    private AttachmentEntity createAttachment(
            String conversationId, String fileName, @Nullable String content, String sourceUrl) {
        OffsetDateTime now = OffsetDateTime.now();
        AttachmentEntity entity = new AttachmentEntity();
        entity.setOwnerType("chat");
        entity.setConversationId(conversationId);
        entity.setFileName(fileName);
        entity.setContentType("text/markdown");
        entity.setFileSize(content != null ? content.getBytes().length : 0);
        entity.setContent(content);
        entity.setSourceUrl(sourceUrl);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return entity;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
