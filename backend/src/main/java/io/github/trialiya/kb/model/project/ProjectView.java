package io.github.trialiya.kb.model.project;

/**
 * A project as the UI sees it: what to show in the selector and whether writes are on.
 *
 * <p>Deliberately not {@link Project}: that one carries the path on disk, and the chat panel has no
 * business knowing where a repository is mounted. Filesystem paths stay in the admin endpoints.
 */
public record ProjectView(String id, String label, boolean editEnabled) {

    public static ProjectView of(Project project) {
        return new ProjectView(project.id(), project.label(), project.editEnabled());
    }
}
