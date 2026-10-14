package io.github.trialiya.kb.functions;

import io.github.trialiya.kb.model.tool.ToolResult;
import io.github.trialiya.kb.service.file.git.GitRegistry;
import io.github.trialiya.kb.service.file.git.GitService;
import io.github.trialiya.kb.tools.ProjectContext;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.model.ToolContext;

/**
 * What every read-only Git tool class shares, held by each as a field: which repository a call reads, and the answer
 * wrapper that names it. The tools themselves live in {@link GitFunction} (the working tree: tree,
 * search, content, grep, uncommitted changes) and {@link GitHistoryFunction} (commits and line
 * history).
 *
 * <p>Which repository is decided per call, not per bean: the project comes from the run's {@link
 * ToolContext} (see {@code ProjectContext}) and is resolved through {@code GitRegistry}, so a call
 * that names no project reads the default one. The {@code ToolContext} parameter is not part of a
 * tool's schema, so the model neither sees nor fills it in.
 *
 * <p>Every tool here additionally takes an optional {@code project} argument that overrides the
 * context's project for that one call — the model's way to ask a cross-project question ("how does
 * A do this, versus B") without switching the chat's project, and the way it reaches a repository
 * the chat selected earlier and has since moved off. Which ids it may name it learns from the
 * prompt ({@code ProjectPromptService}); an id nobody configured fails loudly in {@code
 * ProjectCatalog#require} rather than quietly reading something else.
 *
 * <p>Every answer here is wrapped in a {@link ToolResult}, whose top-level {@code project} field
 * names the repository that actually served it — one field per response, not per item, since a call
 * reads exactly one repository. A model that forgets which call carried the override still knows
 * what it is looking at, and the same field is what {@code ToolInvocationCollector#hasSeenFile}
 * checks so a file seen in one repository never counts as "seen" for a write into another.
 *
 * <p>The edit tools ({@code GitEditFunction}) deliberately have no such argument: writes always
 * land in the project the user selected for the chat.
 *
 * <p><b>Security constraints:</b> all operations are strictly read-only. Only files tracked by Git
 * are accessible — an untracked file is refused even if it exists on disk, unless the project
 * configures {@code allow-globs} and the path falls inside them, which opens that named area as it
 * is on disk, {@code .gitignore} included. Binary files come back without content; a file larger
 * than 512 KB read whole comes back as a head+tail excerpt, and a line range stops at the same
 * 512 KB, so neither way of reading brings a big file into the model context in full.
 */
final class GitReads {

    private final GitRegistry gitRegistry;

    GitReads(GitRegistry gitRegistry) {
        this.gitRegistry = gitRegistry;
    }

    /**
     * The repository this call works on: the one the model named, else the run's own project, else
     * the default one. Every read tool here resolves through this — naming a project is always the
     * model's option, never its obligation.
     */
    GitService git(@Nullable ToolContext context, @Nullable String project) {
        return gitRegistry.forProject(ProjectContext.resolve(context, project));
    }

    /**
     * Ответ инструмента вместе с id репозитория, который на него ответил.
     *
     * <p>Id берётся у самого {@link GitService}, а не у аргумента вызова: аргумент мог быть пуст
     * (разрешится в проект прогона) или назвать проект неканонично, и в обоих случаях модели нужен
     * тот id, которым потом подписывается ссылка на файл.
     */
    static <T> ToolResult<T> answer(GitService git, T payload) {
        return new ToolResult<>(git.project().id(), payload);
    }

    /** {@link #answer(GitService, Object)} of a bounded answer, saying whether it was cut. */
    static <T> ToolResult<T> answer(GitService git, T payload, boolean truncated) {
        return new ToolResult<>(git.project().id(), payload, truncated);
    }
}
