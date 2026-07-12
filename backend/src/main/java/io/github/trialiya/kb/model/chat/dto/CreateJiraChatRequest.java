package io.github.trialiya.kb.model.chat.dto;

import org.jspecify.annotations.Nullable;

/**
 * Request body for creating a JIRA-linked chat.
 *
 * @param jiraUrl required — URL of the JIRA issue (e.g. https://host/browse/PROJ-123)
 * @param confluenceUrl optional — URL of a related Confluence page
 * @param title optional — custom chat title; if blank, auto-generated from issue key
 */
public record CreateJiraChatRequest(
        String jiraUrl, @Nullable String confluenceUrl, @Nullable String title) {}
