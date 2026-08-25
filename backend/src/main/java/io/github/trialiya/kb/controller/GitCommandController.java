package io.github.trialiya.kb.controller;

import io.github.trialiya.kb.model.git.dto.GitCommandResult;
import io.github.trialiya.kb.service.file.git.GitBusyException;
import io.github.trialiya.kb.service.file.git.GitCommandFailedException;
import io.github.trialiya.kb.service.file.git.GitRegistry;
import io.github.trialiya.kb.service.file.git.GitService;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The git commands a <b>user</b> runs on a repository from the files panel.
 *
 * <p>Apart from {@link GitController} because these change the repository, and that one is
 * read-only by design; one endpoint per verb rather than one that takes a command, because the
 * arguments, the preconditions and the failures differ for each — and because an endpoint that
 * accepts a command is a command line, which this deliberately is not.
 *
 * <p>None of this is reachable by the model: no tool calls these, and the system prompt says
 * nothing about them. Every call is a person's click, authenticated like the rest of the API and
 * permitted per project by {@code kb.projects[].git-commands} — {@link
 * GitRegistry#requireGitCommands} is the gate, and it refuses a read-only working tree whatever the
 * configuration says.
 *
 * <p>Each answer carries the branch state the command left behind ({@link GitCommandResult}), so a
 * client never has to draw the state it had before the command it just ran.
 */
@RestController
@RequestMapping("/api/git")
public class GitCommandController {

    private final GitRegistry gitRegistry;

    public GitCommandController(GitRegistry gitRegistry) {
        this.gitRegistry = gitRegistry;
    }

    /**
     * Updates the remote-tracking refs ({@code git fetch --prune --no-tags}) — what gives the
     * "behind" counter its meaning, since that is read off those refs.
     *
     * <p>The working tree is untouched: nothing is merged, no file changes, and a pull stays a
     * separate step the user asks for. This is the one command that is safe to offer next to a
     * counter, which is why it comes first.
     */
    @PostMapping("/fetch")
    public GitCommandResult fetch(
            @RequestParam(name = "project", required = false) @Nullable String project) {
        return run(project, GitService::fetch);
    }

    /**
     * Resolves the project, checks the permission and maps the ways a command can fail onto status
     * codes: an unknown project is the caller's mistake, a project that offers no commands is a
     * deployment's decision, a busy repository is a retry, and git's own refusal is git's message
     * passed through — its wording ("Permission denied (publickey)", "couldn't find remote ref") is
     * what tells the user what to do next, and rewording it would only lose that.
     */
    private GitCommandResult run(
            @Nullable String project, Function<GitService, GitCommandResult> command) {
        GitService git;
        try {
            git = gitRegistry.requireGitCommands(project);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (IllegalStateException e) {
            // A repository that never opened is the deployment's problem and not this project's
            // policy — the same 503 the read endpoints answer with. Everything else here is the
            // configuration saying no, which no retry will change.
            throw new ResponseStatusException(
                    gitRegistry.isAvailable(project)
                            ? HttpStatus.FORBIDDEN
                            : HttpStatus.SERVICE_UNAVAILABLE,
                    e.getMessage());
        }
        try {
            return command.apply(git);
        } catch (GitBusyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        } catch (GitCommandFailedException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        }
    }

    /**
     * Renders the refusal with its reason in the body.
     *
     * <p>Here rather than through {@code server.error.include-message}: that switch is global, and
     * turning it on would put the message of every exception in the application into a response.
     * These reasons are written to be read — git's own output, or a sentence about the project's
     * configuration — and they are the only thing the panel can show a user who cannot see the log.
     */
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<GitCommandError> refusal(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).body(new GitCommandError(e.getReason()));
    }

    /** The body of a refused command: git's message, or ours about the project. */
    public record GitCommandError(@Nullable String message) {}
}
