package io.github.trialiya.kb.service.file.git;

import io.github.trialiya.kb.model.git.dto.GitCapabilities;
import io.github.trialiya.kb.model.project.Project;
import io.github.trialiya.kb.model.project.ProjectOptions;
import io.github.trialiya.kb.model.project.ProjectView;
import io.github.trialiya.kb.service.file.outline.OutlineService;
import io.github.trialiya.kb.service.file.project.ProjectCatalog;
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
 * <p>A repository that fails to open — a mount that never arrived, a path that is no Git working
 * tree — costs its own project and nothing else: the server starts, the other repositories serve,
 * and calls naming the broken one are refused by name. The exception is the default project, which
 * every caller that names none receives: without it the server does not work at all, so it still
 * fails startup.
 *
 * <p>This is also the single answer to "may the model write here": the configured intent ({@code
 * Project#editEnabled}) and the working tree's own permissions are two halves of one question, and
 * the {@code editFile} tool, the {@code runScript} write methods ({@code ScriptEditPolicy}) and the
 * Settings panel all have to give the same answer.
 *
 * <p>The user's own git commands are gated the same way and in the same place ({@link
 * #gitCommandsAllowed}), from a configuration section of their own: what a person may do to the
 * repository through the UI is a separate grant from what the model may write into the working
 * tree.
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
            try {
                services.put(project.id(), new GitService(project, outlineService));
            } catch (RuntimeException e) {
                // Один не доехавший mount не должен уносить сервер: остальные репозитории
                // обслуживаются, а этот отвечает отказом по имени проекта (см. forProject).
                // Дефолтный — исключение: его получает всякий, кто проект не назвал, и сервер
                // без него не работает, а не работает частично.
                if (project.id().equals(catalog.defaultProject().id())) {
                    throw e;
                }
                log.error(
                        "Project {}: repository at {} could not be opened — the project stays"
                                + " configured, but every call to it will be refused: {}",
                        project.id(),
                        project.path(),
                        e.getMessage());
            }
        }
        this.byProjectId = Map.copyOf(services);
        for (Project project : catalog.projects()) {
            // Не открывшийся репозиторий сюда не попадает: про него уже сказано выше и по делу, а
            // editsAllowed отвечает «нет» и на него тоже — предупреждение про права на дерево
            // послало бы разбираться с монтированием ro вместо не доехавшего mount'а.
            if (!byProjectId.containsKey(project.id())) {
                continue;
            }
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

    /**
     * The repository of {@code projectId}; {@code null} — the default project. A configured project
     * whose repository could not be opened at startup (a mount that never arrived) is refused here
     * by name: the failure belongs to that project, not to the server.
     */
    public GitService forProject(@Nullable String projectId) {
        Project project = catalog.require(projectId);
        GitService service = byProjectId.get(project.id());
        if (service == null) {
            throw new IllegalStateException(
                    "Project \""
                            + project.id()
                            + "\" is unavailable: its repository at "
                            + project.path()
                            + " could not be opened at startup");
        }
        return service;
    }

    /**
     * Whether this project's repository actually opened at startup — the same fact {@link
     * ProjectView#available()} carries to the selector, asked directly where no view is built:
     * naming an unopened project in the prompt would only buy the model a refusal.
     */
    public boolean isAvailable(@Nullable String projectId) {
        return catalog.find(projectId).map(p -> byProjectId.containsKey(p.id())).orElse(false);
    }

    /** The repository every caller that names no project gets. */
    public GitService defaultProject() {
        return forProject(null);
    }

    /**
     * What the project selector offers — the list plus the entry a chat gets when it stores none.
     * Built here rather than in {@code ProjectCatalog} because one field of it is only known here:
     * whether the project's repository actually opened (see {@link ProjectView#available()}).
     */
    public ProjectOptions options() {
        return new ProjectOptions(
                catalog.defaultProject().id(),
                catalog.projects().stream()
                        .map(p -> ProjectView.of(p, byProjectId.containsKey(p.id())))
                        .toList());
    }

    /**
     * Whether two ids name the same repository, {@code null} and the default project's own id
     * included. Asked where "did the caller name a project other than this run's" has to be
     * answered ({@code ScriptFunction#runScript}): a chat that stored no project runs on the
     * default one, so comparing the raw ids would read an explicit "the default project" as a
     * switch away from it.
     */
    public boolean sameProject(@Nullable String a, @Nullable String b) {
        return catalog.require(a).id().equals(catalog.require(b).id());
    }

    /**
     * Whether working-tree writes are available for this project right now: configured <em>and</em>
     * physically possible. A read-only mount withholds them whatever the configuration says.
     */
    public boolean editsAllowed(@Nullable String projectId) {
        Project project = catalog.require(projectId);
        GitService service = byProjectId.get(project.id());
        // Asked of the filesystem on every call rather than cached: a mount can be remounted
        // read-only under a running server, and the honest answer is the current one. A project
        // whose repository never opened accepts nothing — the question is answered, not thrown.
        return project.editEnabled() && service != null && service.isRepoWritable();
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

    // ── The user's git commands ─────────────────────────────────────────────

    /**
     * Whether a user may run git commands on this project right now: configured <em>and</em>
     * physically possible, the same pair {@link #editsAllowed} answers. Every one of these commands
     * writes — a checkout moves files, a stash rewrites them — so a read-only mount withholds them
     * all, including the ones that only read a remote: a fetch that cannot write {@code .git} is no
     * fetch.
     *
     * <p>This is not a model capability: no tool is derived from it, and no prompt mentions it. It
     * gates the endpoints a person's click reaches.
     */
    public boolean gitCommandsAllowed(@Nullable String projectId) {
        Project project = catalog.require(projectId);
        GitService service = byProjectId.get(project.id());
        return project.gitCommandsEnabled() && service != null && service.isRepoWritable();
    }

    /**
     * Whether {@code push} is among the commands available for this project — the one that
     * publishes outside the deployment, hence a grant of its own on top of {@link
     * #gitCommandsAllowed}.
     */
    public boolean gitPushAllowed(@Nullable String projectId) {
        return catalog.require(projectId).gitPushEnabled() && gitCommandsAllowed(projectId);
    }

    /**
     * What the UI may offer for this project — the capabilities behind the buttons, answered for
     * the state the repository is in right now rather than for the configuration alone.
     */
    public GitCapabilities capabilities(@Nullable String projectId) {
        Project project = catalog.require(projectId);
        return new GitCapabilities(
                project.id(),
                isAvailable(project.id()),
                gitCommandsAllowed(project.id()),
                gitPushAllowed(project.id()));
    }
}
