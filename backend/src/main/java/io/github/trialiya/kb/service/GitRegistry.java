package io.github.trialiya.kb.service;

import io.github.trialiya.kb.model.project.Project;
import jakarta.annotation.PreDestroy;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Which {@link GitService} serves a given project — one instance per configured repository, built
 * at startup and reused, because a {@code GitService} owns an open JGit repository (index, object
 * database, file descriptors) and one per call would leak them.
 *
 * <p>A {@code null} project id is not an error and not a fourth case: it means "the caller does not
 * know which project", which is every caller today, and resolves to {@code
 * ProjectCatalog#defaultProject()} — the first configured project. An id that names no configured
 * project does fail, so a stale link cannot quietly read a different repository.
 *
 * <p>This is also the single answer to "may the model write here": the configured intent ({@code
 * Project#editEnabled}) and the working tree's own permissions are two halves of one question, and
 * the {@code editFile} tool, the {@code runScript} write methods ({@code ScriptEditPolicy}) and the
 * Settings panel all have to give the same answer.
 */
@Slf4j
@Service
public class GitRegistry {

    private final ProjectCatalog catalog;
    private final Map<String, GitService> byProjectId;

    public GitRegistry(ProjectCatalog catalog, OutlineService outlineService) {
        this.catalog = catalog;
        Map<String, GitService> services = new LinkedHashMap<>();
        for (Project project : catalog.projects()) {
            services.put(project.id(), new GitService(project, outlineService));
        }
        this.byProjectId = Map.copyOf(services);
        for (Project project : catalog.projects()) {
            if (project.editEnabled() && !editsAllowed(project.id())) {
                log.warn(
                        "Project {}: edits are enabled in configuration, but the working tree at {}"
                                + " is not writable (read-only mount?) — writes are withheld",
                        project.id(),
                        project.path());
            }
        }
    }

    @PreDestroy
    void closeRepositories() {
        byProjectId.values().forEach(GitService::closeRepository);
    }

    /** The repository of {@code projectId}; {@code null} — the default project. */
    public GitService forProject(@Nullable String projectId) {
        // require() already refused every id this map does not hold — the two are built from the
        // same catalog — so a miss here would be a bug in the registry, not a caller error.
        GitService service = byProjectId.get(catalog.require(projectId).id());
        if (service == null) {
            throw new IllegalStateException("No repository opened for project: " + projectId);
        }
        return service;
    }

    /** The repository every caller that names no project gets. */
    public GitService defaultProject() {
        return forProject(null);
    }

    /**
     * Whether working-tree writes are available for this project right now: configured <em>and</em>
     * physically possible. A read-only mount withholds them whatever the configuration says.
     */
    public boolean editsAllowed(@Nullable String projectId) {
        Project project = catalog.require(projectId);
        // Asked of the filesystem on every call rather than cached: a mount can be remounted
        // read-only under a running server, and the honest answer is the current one.
        return project.editEnabled() && forProject(project.id()).isRepoWritable();
    }

    /**
     * The repository of {@code projectId}, provided writes are available for it — otherwise a
     * refusal naming the project, which for a tool call reaches the model through the error channel
     * instead of failing as an I/O error halfway through a write.
     */
    public GitService requireEditable(@Nullable String projectId) {
        Project project = catalog.require(projectId);
        if (!editsAllowed(project.id())) {
            throw new IllegalStateException(
                    "Project \"" + project.id() + "\" is read-only: file edits are not available");
        }
        return forProject(project.id());
    }

    /** Whether any configured project accepts writes — what decides if the edit tools exist. */
    public boolean anyEditable() {
        return catalog.projects().stream().anyMatch(p -> editsAllowed(p.id()));
    }
}
