package io.github.trialiya.kb.model.project;

/**
 * A project as the UI sees it: what to show in the selector, and nothing else.
 *
 * <p>Deliberately not {@link Project}: that one carries the path on disk, and the chat panel has no
 * business knowing where a repository is mounted. Filesystem paths stay in the admin endpoints, and
 * so does {@code editEnabled} — whether writes are on is a question about the tools, which the
 * selector neither asks nor answers.
 *
 * @param available whether this project's repository actually opened at startup. {@code false} — it
 *     is configured but its mount never arrived, so every call naming it is refused; the selector
 *     marks it instead of letting the user find out by getting an error. Answered by {@code
 *     GitRegistry}: the catalogue knows the configuration, only the registry knows what opened
 */
public record ProjectView(String id, String label, boolean available) {

    public static ProjectView of(Project project, boolean available) {
        return new ProjectView(project.id(), project.label(), available);
    }
}
