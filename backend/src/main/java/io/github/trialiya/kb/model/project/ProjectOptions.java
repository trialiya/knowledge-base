package io.github.trialiya.kb.model.project;

import java.util.List;

/**
 * What the project selector offers, and which entry is preselected when the chat has chosen none.
 *
 * <p>The default is named rather than left implicit as "the first one": a chat stores {@code null}
 * until the user picks something, so a client that only got the list would have to encode the
 * first-in-the-list rule itself — and would keep encoding it after the rule moves.
 *
 * @param defaultProject id of the project a chat without a stored one runs on
 * @param projects every configured project, in configuration order
 */
public record ProjectOptions(String defaultProject, List<ProjectView> projects) {}
