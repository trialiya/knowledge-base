package io.github.trialiya.kb.controller;

import io.github.trialiya.kb.model.git.dto.GitCommandResult;
import io.github.trialiya.kb.service.chat.git.ChatGitLog;
import io.github.trialiya.kb.service.file.git.GitBusyException;
import io.github.trialiya.kb.service.file.git.GitCommandFailedException;
import io.github.trialiya.kb.service.file.git.GitRegistry;
import io.github.trialiya.kb.service.file.git.GitService;
import java.util.List;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
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
 *
 * <p>The optional {@code chat} parameter says the command was run from a chat rather than the files
 * panel. It changes two things and nothing else: the command is refused while that chat has a run
 * in flight, and what it did is written into that chat's history as a row of its own (see {@code
 * ChatHistoryService.appendGitEvent}) — where the user sees the output again and the model learns
 * the repository moved. The verbs, the permissions and the failures stay the same, because it is
 * the same command from another surface.
 */
@RestController
@RequestMapping("/api/git")
public class GitCommandController {

    private final GitRegistry gitRegistry;
    private final ChatGitLog chatGitLog;

    public GitCommandController(GitRegistry gitRegistry, ChatGitLog chatGitLog) {
        this.gitRegistry = gitRegistry;
        this.chatGitLog = chatGitLog;
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
            @RequestParam(name = "project", required = false) @Nullable String project,
            @RequestParam(name = "chat", required = false) @Nullable String chat) {
        return run("fetch", project, chat, GitService::fetch);
    }

    /**
     * Brings in what the upstream has ({@code git pull --ff-only}).
     *
     * <p>Fast-forward only: diverged histories come back as a {@code 422} instead of a merge commit
     * nobody asked for. A pull that leaves conflicts behind is visible in the branch status ({@code
     * merging}) and undone by {@code /merge/abort}.
     */
    @PostMapping("/pull")
    public GitCommandResult pull(
            @RequestParam(name = "project", required = false) @Nullable String project,
            @RequestParam(name = "chat", required = false) @Nullable String chat) {
        return run("pull", project, chat, GitService::pull);
    }

    /**
     * Publishes the current branch ({@code git push}), setting the upstream on a branch that tracks
     * nothing yet when the repository has exactly one remote.
     *
     * <p>The one command behind a grant of its own ({@code git-commands.push-enabled}): it sends
     * this repository's content outside the deployment. Never forced — a rejected push means the
     * remote moved, and the answer to that is a pull, not an overwrite.
     */
    @PostMapping("/push")
    public GitCommandResult push(
            @RequestParam(name = "project", required = false) @Nullable String project,
            @RequestParam(name = "chat", required = false) @Nullable String chat) {
        return run("push", project, chat, GitService::push, true);
    }

    /**
     * Moves the checkout onto another branch, creating it at the current commit with {@code
     * create=true} ({@code git switch} / {@code git switch -c}).
     *
     * <p>Never forced. A switch that would overwrite uncommitted changes comes back as a {@code
     * 422} naming those files, and the user decides what to do with them — the server does not
     * stash them on its own, because a pop can conflict in turn and nobody would know where the
     * work went.
     */
    @PostMapping("/switch")
    public GitCommandResult switchBranch(
            @RequestParam("branch") String branch,
            @RequestParam(name = "create", defaultValue = "false") boolean create,
            @RequestParam(name = "project", required = false) @Nullable String project,
            @RequestParam(name = "chat", required = false) @Nullable String chat) {
        return run("switch " + branch, project, chat, git -> git.switchBranch(branch, create));
    }

    /** Puts the tracked changes aside ({@code git stash push}). */
    @PostMapping("/stash")
    public GitCommandResult stashPush(
            @RequestParam(name = "project", required = false) @Nullable String project,
            @RequestParam(name = "chat", required = false) @Nullable String chat) {
        return run("stash", project, chat, GitService::stashPush);
    }

    /**
     * Brings the newest stash back and drops it; a conflicting pop keeps it ({@code stash pop}).
     */
    @PostMapping("/stash/pop")
    public GitCommandResult stashPop(
            @RequestParam(name = "project", required = false) @Nullable String project,
            @RequestParam(name = "chat", required = false) @Nullable String chat) {
        return run("stash pop", project, chat, GitService::stashPop);
    }

    /**
     * Commits the tracked changes — the same files the panel lists under "changes", the assistant's
     * staged edits included. Refused when the repository has no {@code user.name}/{@code
     * user.email}: the identity is the deployment's to set, not this application's to invent.
     *
     * <p>{@code paths} narrows it to the files the commit dialog ticked; without them the whole
     * tracked set goes in, which is what the plain button in the files panel means.
     */
    @PostMapping("/commit")
    public GitCommandResult commit(
            @RequestParam("message") String message,
            @RequestParam MultiValueMap<String, String> form,
            @RequestParam(name = "project", required = false) @Nullable String project,
            @RequestParam(name = "chat", required = false) @Nullable String chat) {
        // Read off the raw form rather than bound as a List<String>: a single value bound to a
        // collection is split on commas by the default converter, and a comma is a perfectly
        // ordinary character in a file name — one ticked "docs/a,b.md" would arrive as two paths
        // that match nothing.
        //
        // No paths at all is not the same as none selected: the commit dialog always names what it
        // ticked, and an older caller that names nothing still means "everything tracked".
        List<String> selected = form.getOrDefault("paths", List.of());
        return run("commit", project, chat, git -> git.commit(message, selected));
    }

    /**
     * Restores one tracked file to its committed state ({@code git restore}).
     *
     * <p>The one command here that destroys work instead of moving it — hence one path per call and
     * no "discard everything": what it throws away includes whatever the assistant wrote there, and
     * the UI has to be able to name the file before asking.
     */
    @PostMapping("/discard")
    public GitCommandResult discard(
            @RequestParam("path") String path,
            @RequestParam(name = "project", required = false) @Nullable String project,
            @RequestParam(name = "chat", required = false) @Nullable String chat) {
        return run("discard " + path, project, chat, git -> git.discard(path));
    }

    /**
     * Leaves an unfinished merge ({@code git merge --abort}) — the way out of a conflicted working
     * tree, and what keeps a conflicted pull from being a dead end.
     */
    @PostMapping("/merge/abort")
    public GitCommandResult abortMerge(
            @RequestParam(name = "project", required = false) @Nullable String project,
            @RequestParam(name = "chat", required = false) @Nullable String chat) {
        return run("merge --abort", project, chat, GitService::abortMerge);
    }

    /**
     * Resolves the project, checks the permission and maps the ways a command can fail onto status
     * codes: an unknown project is the caller's mistake, a project that offers no commands is a
     * deployment's decision, a busy repository is a retry, and git's own refusal is git's message
     * passed through — its wording ("Permission denied (publickey)", "couldn't find remote ref") is
     * what tells the user what to do next, and rewording it would only lose that.
     */
    private GitCommandResult run(
            String verb,
            @Nullable String project,
            @Nullable String chat,
            Function<GitService, GitCommandResult> command) {
        return run(verb, project, chat, command, false);
    }

    /**
     * @param verb the command in the words the chat will remember it by. Passed in rather than read
     *     off the result, because a refusal has no result and it is exactly the refusals the chat
     *     most needs to name
     * @param push whether this command also needs the project's push grant — the one permission
     *     that is not implied by "may run git commands here"
     */
    private GitCommandResult run(
            String verb,
            @Nullable String project,
            @Nullable String chat,
            Function<GitService, GitCommandResult> command,
            boolean push) {
        GitService git;
        try {
            git =
                    push
                            ? gitRegistry.requireGitPush(project)
                            : gitRegistry.requireGitCommands(project);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        } catch (IllegalStateException e) {
            // A repository that never opened is the deployment's problem and not this project's
            // policy — the same 503 the read endpoints answer with. Everything else here is the
            // configuration saying no, which no retry will change.
            throw new ResponseStatusException(
                    gitRegistry.isAvailable(project)
                            ? HttpStatus.FORBIDDEN
                            : HttpStatus.SERVICE_UNAVAILABLE,
                    e.getMessage(),
                    e);
        }
        // Whose chat it is, and the chat's claim held for as long as the command runs — taken
        // before anything is executed, so a refusal leaves the working tree untouched, and held
        // rather than merely checked, so no run can start alongside the command and race it into
        // the same history (see ChatGitLog.claimIdleAndOwned).
        final String claim = chat == null ? null : chatGitLog.claimIdleAndOwned(chat);
        try {
            return runClaimed(verb, project, chat, command, git);
        } finally {
            if (claim != null) {
                chatGitLog.release(chat, claim);
            }
        }
    }

    /** The command itself, under the chat's claim: run it, and record what it did or refused. */
    private GitCommandResult runClaimed(
            String verb,
            @Nullable String project,
            @Nullable String chat,
            Function<GitService, GitCommandResult> command,
            GitService git) {
        try {
            final GitCommandResult result = command.apply(git);
            if (chat != null) {
                chatGitLog.record(
                        chat,
                        result.command(),
                        project,
                        true,
                        result.output(),
                        result.status().current());
            }
            return result;
        } catch (IllegalArgumentException e) {
            // An unusable path — the same refusal RepoPaths gives every other endpoint, and the
            // caller's mistake rather than git's.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        } catch (GitBusyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage(), e);
        } catch (GitCommandFailedException e) {
            // git refused, and that is an outcome the chat records: the user will want to read the
            // reason again, and the model must not take the command for done. A busy repository
            // and a bad argument are not outcomes — nothing was attempted.
            if (chat != null) {
                chatGitLog.record(chat, verb, project, false, String.valueOf(e.getMessage()), null);
            }
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage(), e);
        } catch (IllegalStateException e) {
            // The command could not be run at all: no git binary, an unreadable HEAD, a reader
            // that never drained. Ours to fix rather than the user's, but it still travels with
            // its message — "Cannot run git fetch" is the whole diagnosis, and a bare 500 sends
            // whoever is looking at the panel to the server log for a sentence we already have.
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage(), e);
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
