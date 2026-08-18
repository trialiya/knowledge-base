package io.github.trialiya.kb.service;

import io.github.trialiya.kb.config.model.GitProperties;
import io.github.trialiya.kb.config.model.ProjectProperties;
import io.github.trialiya.kb.config.model.ProjectProperties.ProjectOption;
import io.github.trialiya.kb.model.project.Project;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * The configured projects, resolved once at startup: {@code kb.projects} with its defaults applied,
 * or — when that list is empty — the single project the legacy {@code kb.git.project-path}
 * describes, so a deployment that only sets {@code PROJECT_PATH} needs no config change.
 *
 * <p>This is also where "no project named" is answered, and it is answered in exactly one place:
 * {@link #defaultProject()} is the <em>first</em> entry of the list. Every caller that may not know
 * the project — a tool call with nothing in its {@code ToolContext}, an endpoint with no parameter
 * for it — resolves through here rather than reaching for a configuration value of its own.
 *
 * <p>Anything that cannot be satisfied fails the context: a bad path is not a project whose tools
 * merely return errors, it is a deployment that was meant to serve a repository and cannot.
 */
@Slf4j
@Service
public class ProjectCatalog {

    /** Meant to survive a URL segment unescaped, so no spaces, slashes or uppercase. */
    private static final Pattern SAFE_ID = Pattern.compile("[a-z0-9][a-z0-9._-]*");

    /** Id of the project synthesized from {@code kb.git.project-path}. */
    private static final String LEGACY_ID = "default";

    private final List<Project> projects;

    public ProjectCatalog(ProjectProperties projectProperties, GitProperties gitProperties) {
        this.projects = resolve(projectProperties.projects(), gitProperties);
        log.info(
                "Projects: {}",
                projects.stream()
                        .map(p -> p.id() + " → " + p.path() + (p.editEnabled() ? " (rw)" : ""))
                        .toList());
    }

    /** Every configured project, in configuration order. Never empty. */
    public List<Project> projects() {
        return projects;
    }

    /**
     * The project a caller gets when it names none. First in the list — the rule the whole
     * single-project compatibility rests on, so it is stated once here and nowhere else.
     */
    public Project defaultProject() {
        return projects.getFirst();
    }

    /** The project {@code projectId} names; empty for an unknown id. {@code null} → the default. */
    public Optional<Project> find(@Nullable String projectId) {
        if (!StringUtils.hasText(projectId)) {
            return Optional.of(defaultProject());
        }
        return projects.stream().filter(p -> p.id().equals(projectId)).findFirst();
    }

    /**
     * As {@link #find}, but an unknown id is a caller error rather than a silent fallback: reading
     * the wrong repository because a project was renamed is worse than a failed call that says so.
     */
    public Project require(@Nullable String projectId) {
        return find(projectId)
                .orElseThrow(
                        () ->
                                new IllegalArgumentException(
                                        "Unknown project: "
                                                + projectId
                                                + " (configured: "
                                                + projects.stream().map(Project::id).toList()
                                                + ")"));
    }

    private static List<Project> resolve(List<ProjectOption> options, GitProperties gitProperties) {
        if (options.isEmpty()) {
            String path = gitProperties.projectPath();
            if (!StringUtils.hasText(path)) {
                throw new IllegalStateException(
                        "No project configured: set kb.projects[0].path (or the legacy"
                                + " kb.git.project-path)");
            }
            return List.of(
                    new Project(LEGACY_ID, LEGACY_ID, absolute(path), gitProperties.editEnabled()));
        }
        // One project is all the rest of the code can currently reach: nothing chooses between
        // them yet — not a chat, not an endpoint, not a link — so a second entry would be
        // configuration that silently does nothing.
        if (options.size() > 1) {
            throw new IllegalStateException(
                    "kb.projects: several projects are configured "
                            + options.stream().map(ProjectOption::id).toList()
                            + ", but only one is supported for now — the project cannot be chosen"
                            + " per chat yet, so every extra entry would be unreachable");
        }
        List<Project> resolved = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        for (ProjectOption option : options) {
            String id = option.id();
            if (id == null || !SAFE_ID.matcher(id).matches()) {
                throw new IllegalStateException(
                        "kb.projects: id \""
                                + id
                                + "\" is not usable — lowercase letters, digits, '.', '_' and '-'"
                                + " only, starting with a letter or a digit");
            }
            if (!ids.add(id)) {
                throw new IllegalStateException("kb.projects: duplicate id \"" + id + "\"");
            }
            if (!StringUtils.hasText(option.path())) {
                throw new IllegalStateException("kb.projects[" + id + "]: path is required");
            }
            resolved.add(
                    new Project(
                            id,
                            option.displayLabel(),
                            absolute(option.path()),
                            option.editEnabled() != null
                                    ? option.editEnabled()
                                    : gitProperties.editEnabled()));
        }
        return List.copyOf(resolved);
    }

    private static Path absolute(String path) {
        return Path.of(path).toAbsolutePath().normalize();
    }
}
