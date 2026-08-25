package io.github.trialiya.kb.service.file.git;

import io.github.trialiya.kb.model.git.dto.GitBranchStatus;
import io.github.trialiya.kb.model.git.dto.GitCommandResult;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.CheckoutConflictException;
import org.eclipse.jgit.api.errors.EmptyCommitException;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.RefAlreadyExistsException;
import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.eclipse.jgit.api.errors.StashApplyFailureException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryState;
import org.eclipse.jgit.lib.UserConfig;
import org.eclipse.jgit.revwalk.RevCommit;

/**
 * The git commands a <b>user</b> runs on one repository from the UI. One instance per project,
 * owned by {@link GitService} the way {@link GitWriter} is — and reached only through {@code
 * GitRegistry.requireGitCommands}, which is where the project's permission is checked.
 *
 * <p>The model never gets here: no {@code @Tool} calls these, and nothing about them is in the
 * system prompt. Each is a person's explicit action in the interface.
 *
 * <p><b>JGit or a subprocess, and why both.</b> The line runs where the user's own data does. A
 * command that needs nothing but this repository — switch, stash, commit, discard — is JGit's,
 * in-process: it answers in types ({@code MergeResult} carries the conflicting paths) rather than
 * in text somebody has to parse back. A command that talks to a remote needs credentials that
 * belong to the host — an ssh agent, a credential helper, an {@code insteadOf} rewrite — and those
 * the system {@code git} already knows; teaching JGit each of them would mean reproducing the
 * environment instead of using it. What JGit simply cannot do ({@code merge --abort}) also goes
 * through the subprocess. There is no shell either way — {@link ProcessBuilder} takes an argv array
 * — and the verb is always ours: user input reaches these commands as a validated branch name, a
 * repo-relative path or a commit message, never as an argument list.
 *
 * <p><b>One at a time.</b> Every command writes something: a fetch moves refs, the operations to
 * come move the working tree. Two of them overlapping would interleave those writes, and the second
 * caller would read a state neither command produced — so a project runs one at a time and a
 * request that finds one running is refused rather than queued behind it.
 */
@Slf4j
class GitCommands {

    /**
     * How long a network command may run before it is killed. Long enough for a first fetch of a
     * large repository over a slow link, short enough that a hung connection frees the project's
     * lock within a page's patience rather than holding it until the server restarts.
     */
    private static final long TIMEOUT_SECONDS = 120;

    /**
     * How long the output is still collected after the process is gone. Only ever the tail already
     * in the pipe, so this is a guard against a stuck reader thread rather than a wait.
     */
    private static final long OUTPUT_DRAIN_SECONDS = 5;

    /** Output beyond this is cut: it goes to a panel, and git's progress can be long. */
    private static final int MAX_OUTPUT_CHARS = 4000;

    /** Abbreviation length for the hash a commit reports back, matching git's own default. */
    private static final int ABBREV_LEN = 7;

    /** Longest commit message accepted — a subject and a body, not a pasted document. */
    private static final int MAX_MESSAGE_CHARS = 4000;

    private final Path root;
    private final GitBranches branches;
    private final Repository repository;
    private final Git git;
    private final RepoPaths paths;

    /**
     * Held for the whole command. Never waited on: a caller that finds it taken is told the
     * repository is busy, because the alternative is a request that blocks for the timeout above
     * while the user watches a spinner for someone else's fetch.
     */
    private final ReentrantLock lock = new ReentrantLock();

    GitCommands(RepoPaths paths, Repository repository, Git git, GitBranches branches) {
        this.paths = paths;
        this.root = paths.root();
        this.repository = repository;
        this.git = git;
        this.branches = branches;
    }

    /**
     * @see GitService#fetch()
     */
    GitCommandResult fetch() {
        // --prune: a branch deleted on the remote otherwise stays in the list forever, and the
        // list is what the branch picker offers. --no-tags keeps a fetch from dragging in every
        // tag of a large repository, which is not what "refresh what I can pull" means.
        return shell("fetch", List.of("git", "fetch", "--prune", "--no-tags"));
    }

    /**
     * @see GitService#pull()
     */
    GitCommandResult pull() {
        requireOnABranch("pull");
        // --ff-only: what the panel offers is "bring in what the remote has", and that is exactly
        // a fast-forward. Anything else means the two histories diverged, and the merge commit
        // git would otherwise write is a decision nobody made in a click — the refusal names the
        // situation and the user resolves it deliberately.
        return shell("pull", List.of("git", "pull", "--ff-only"));
    }

    /**
     * @see GitService#push()
     */
    GitCommandResult push() {
        GitBranchStatus status = requireOnABranch("push");
        if (status.upstream() != null) {
            // No --force, ever, in any spelling: a click must not be able to overwrite a history
            // somebody else is working on. A rejected push is fetch-then-pull territory, and
            // git's own message says exactly that.
            return shell("push", List.of("git", "push"));
        }
        // A branch created in this panel tracks nothing yet, so a plain push would fail on every
        // first push with an instruction to type a command the user has no terminal for. With one
        // remote there is nothing to choose, so the choice is not worth a dialog; with several
        // there is, and guessing which one publishes the work is not ours to do.
        String remote = soleRemote();
        return shell(
                "push -u " + remote + " " + status.current(),
                List.of("git", "push", "--set-upstream", remote, status.current()));
    }

    /** The one remote this repository has, or a refusal naming what to do about it. */
    private String soleRemote() {
        Set<String> remotes = repository.getRemoteNames();
        if (remotes.isEmpty()) {
            throw new GitCommandFailedException(
                    "This repository has no remote to push to. Add one on the host:"
                            + " git remote add origin <url>");
        }
        if (remotes.size() > 1) {
            throw new GitCommandFailedException(
                    "The branch tracks nothing and this repository has several remotes ("
                            + String.join(", ", remotes)
                            + ") — set the upstream on the host to say which one publishes it:"
                            + " git push -u <remote> <branch>");
        }
        return remotes.iterator().next();
    }

    /**
     * The branch state, provided HEAD is on a branch at all.
     *
     * <p>A detached HEAD has nothing to pull into and nothing to publish, and git's own wording for
     * it ("You are not currently on a branch") reaches the user only after the network round trip.
     * Asked here instead, before anything is spent.
     */
    private GitBranchStatus requireOnABranch(String command) {
        GitBranchStatus status = branches.status();
        if (status.detached()) {
            throw new GitCommandFailedException(
                    "HEAD is not on a branch — cannot " + command + ". Switch to one first.");
        }
        if (status.unborn()) {
            throw new GitCommandFailedException(
                    "This branch has no commits yet — nothing to " + command);
        }
        return status;
    }

    // ── Local commands (JGit) ───────────────────────────────────────────────

    /**
     * @see GitService#switchBranch
     */
    GitCommandResult switchBranch(String branch, boolean create) {
        String name = requireBranchName(branch);
        return local(
                create ? "switch -c " + name : "switch " + name,
                () -> {
                    try {
                        git.checkout().setName(name).setCreateBranch(create).call();
                        return "";
                    } catch (RefAlreadyExistsException e) {
                        throw new GitCommandFailedException("Branch already exists: " + name);
                    } catch (RefNotFoundException e) {
                        throw new GitCommandFailedException("No such branch: " + name);
                    } catch (CheckoutConflictException e) {
                        // The one refusal a user acts on rather than reports: git names the files
                        // whose local changes the switch would overwrite, and stashing or
                        // committing them is what unblocks it.
                        throw new GitCommandFailedException(
                                "Uncommitted changes would be overwritten by the switch: "
                                        + String.join(", ", e.getConflictingPaths())
                                        + ". Commit or stash them first.");
                    } catch (GitAPIException e) {
                        throw new GitCommandFailedException(message(e));
                    }
                });
    }

    /**
     * @see GitService#stashPush()
     */
    GitCommandResult stashPush() {
        return local(
                "stash push",
                () -> {
                    try {
                        // Untracked files stay where they are: the ones this project serves are
                        // build output and notes admitted by allow-globs, and sweeping them into a
                        // stash would hide files nobody was asking about.
                        RevCommit stash = git.stashCreate().call();
                        if (stash == null) {
                            throw new GitCommandFailedException("No local changes to stash");
                        }
                        return "Stashed " + stash.getShortMessage();
                    } catch (GitAPIException e) {
                        throw new GitCommandFailedException(message(e));
                    }
                });
    }

    /**
     * @see GitService#stashPop()
     */
    GitCommandResult stashPop() {
        return local(
                "stash pop",
                () -> {
                    try {
                        if (git.stashList().call().isEmpty()) {
                            throw new GitCommandFailedException("The stash is empty");
                        }
                        git.stashApply().call();
                        // Dropped only once the apply succeeded — a pop that conflicts must leave
                        // the stash in place, or the work would exist nowhere but the conflicted
                        // working tree.
                        git.stashDrop().call();
                        return "";
                    } catch (StashApplyFailureException e) {
                        throw new GitCommandFailedException(
                                "The stashed changes conflict with the working tree — the stash is"
                                        + " kept. Resolve the conflict, or commit what you have"
                                        + " first.");
                    } catch (GitAPIException e) {
                        throw new GitCommandFailedException(message(e));
                    }
                });
    }

    /**
     * @see GitService#commit
     */
    GitCommandResult commit(String message) {
        String text = message == null ? "" : message.strip();
        if (text.isEmpty()) {
            throw new GitCommandFailedException("A commit needs a message");
        }
        if (text.length() > MAX_MESSAGE_CHARS) {
            throw new GitCommandFailedException(
                    "The commit message is longer than " + MAX_MESSAGE_CHARS + " characters");
        }
        requireIdentity();
        return local(
                "commit",
                () -> {
                    try {
                        // Everything tracked, staged or not — what the panel showed under
                        // "changes" is what goes in, including the edits the assistant staged.
                        // Untracked files are left out: -A would pull in whatever the working tree
                        // happens to hold, which is not what the review surface displayed.
                        git.add().addFilepattern(".").setUpdate(true).call();
                        // Never signed. Signing needs a key, and this application holds none of
                        // the operator's — with commit.gpgsign on for the host user, JGit would
                        // otherwise fail the commit outright ("No signer for ssh signatures")
                        // instead of recording it. A deployment that wants signed history signs
                        // on the host, where the key is.
                        RevCommit commit =
                                git.commit()
                                        .setMessage(text)
                                        // Без этого JGit молча записывает коммит без единого
                                        // изменения: кнопка «закоммитить» на чистом дереве
                                        // оставила бы в истории пустышку.
                                        .setAllowEmpty(false)
                                        .setSign(false)
                                        .call();
                        return "Committed " + commit.abbreviate(ABBREV_LEN).name();
                    } catch (EmptyCommitException e) {
                        throw new GitCommandFailedException("Nothing to commit");
                    } catch (GitAPIException e) {
                        throw new GitCommandFailedException(message(e));
                    }
                });
    }

    /**
     * @see GitService#discard
     */
    GitCommandResult discard(String filePath) {
        String path = RepoPaths.normalize(filePath);
        return local(
                "restore " + path,
                () -> {
                    try {
                        // Confined like every other path that reaches the working tree: an
                        // admitted untracked file may be a symlink out of the repository, and a
                        // discard must not be the one operation that follows it.
                        paths.confine(path);
                        // Asked before the checkout, because JGit's does nothing at all for a
                        // path HEAD does not have — an untracked file would come back "restored"
                        // while still sitting there, which is the one answer a discard must not
                        // give. Deleting it instead is not this command's business either: it
                        // restores committed state, and an untracked file has none.
                        if (repository.resolve(Constants.HEAD + ":" + path) == null) {
                            throw new GitCommandFailedException(
                                    "Nothing committed at " + path + " to restore it to");
                        }
                        git.checkout().setStartPoint(Constants.HEAD).addPath(path).call();
                        return "";
                    } catch (IOException e) {
                        throw new GitCommandFailedException("Cannot read HEAD for " + path);
                    } catch (RefNotFoundException e) {
                        throw new GitCommandFailedException(
                                "The repository has no commit to restore " + path + " from");
                    } catch (GitAPIException e) {
                        // JGit reports "did not match any file(s) known to git" for a path HEAD
                        // does not have — an untracked file, which has no committed state to go
                        // back to and would have to be deleted instead.
                        throw new GitCommandFailedException(
                                "Cannot restore " + path + ": " + message(e));
                    }
                });
    }

    /**
     * @see GitService#abortMerge()
     */
    GitCommandResult abortMerge() {
        if (repository.getRepositoryState() == RepositoryState.SAFE) {
            throw new GitCommandFailedException("There is no merge in progress");
        }
        // JGit has no equivalent: a hard reset to ORIG_HEAD comes close but loses the distinction
        // between an aborted merge and a discarded commit, and this is the one command a user
        // reaches for precisely because everything else went wrong.
        return shell("merge --abort", List.of("git", "merge", "--abort"));
    }

    /**
     * Refuses a commit when the repository has no identity to sign it with.
     *
     * <p>JGit would not: it falls back to a username and a hostname it makes up from the
     * environment, and a history signed {@code root@a3f9c1b2} is worse than a refusal. The identity
     * belongs to whoever owns the deployment, not to this application, so the message names the two
     * commands that set it rather than offering to invent one.
     */
    private void requireIdentity() {
        UserConfig user = repository.getConfig().get(UserConfig.KEY);
        if (user.isAuthorNameImplicit() || user.isAuthorEmailImplicit()) {
            throw new GitCommandFailedException(
                    "This repository has no commit identity. Set it on the host:"
                            + " git config user.name \"…\" && git config user.email \"…\"");
        }
    }

    /**
     * A branch name this repository would accept, or a refusal.
     *
     * <p>Not an argv guard — nothing here reaches a command line — but the same rejection a user
     * would get from git itself, delivered before the operation starts rather than as a failure
     * halfway through it. The leading dash is refused separately: git's own ref rules allow it, and
     * a branch named like an option is a trap for every tool that later reads this repository.
     */
    private static String requireBranchName(String branch) {
        String name = branch == null ? "" : branch.strip();
        if (name.isEmpty()) {
            throw new GitCommandFailedException("A branch name is required");
        }
        if (name.startsWith("-")) {
            throw new GitCommandFailedException("A branch name cannot start with '-': " + name);
        }
        if (!Repository.isValidRefName(Constants.R_HEADS + name)) {
            throw new GitCommandFailedException("Not a valid branch name: " + name);
        }
        return name;
    }

    /** JGit's message, or the exception's own name when it carries none. */
    private static String message(Exception e) {
        return e.getMessage() == null || e.getMessage().isBlank()
                ? e.getClass().getSimpleName()
                : e.getMessage();
    }

    /**
     * A command's own work — whatever it wants shown as output, JGit's exceptions already mapped.
     */
    private interface Local {
        String call();
    }

    /** Runs an in-process command under the project's lock. */
    private GitCommandResult local(String name, Local command) {
        return run(name, command);
    }

    /** Runs a subprocess command under the project's lock. */
    private GitCommandResult shell(String name, List<String> argv) {
        return run(name, () -> exec(name, argv));
    }

    /**
     * Runs one command in the repository, refusing rather than waiting when the project is busy,
     * and answering with the branch state it left behind.
     *
     * @param name the command as it is reported back — the verb and what the user chose, never an
     *     argv: this is what the panel prints and what the chat will later tell the model
     */
    private GitCommandResult run(String name, Local command) {
        if (!lock.tryLock()) {
            throw new GitBusyException(
                    "Another git command is running for this repository — try again in a moment");
        }
        try {
            String output = command.call();
            return new GitCommandResult(name, output, branches.status());
        } finally {
            lock.unlock();
        }
    }

    /**
     * Runs {@code argv} in the repository root and returns its output, stderr included — git says
     * everything worth showing there, progress and refusals alike.
     *
     * @throws GitCommandFailedException when git exits non-zero; the output is the message, because
     *     git's own wording ("could not read Username", "Permission denied (publickey)") is what
     *     tells the operator what to fix
     */
    private String exec(String name, List<String> argv) {
        ProcessBuilder builder =
                new ProcessBuilder(argv).directory(root.toFile()).redirectErrorStream(true);
        // There is no terminal here and nothing to type into one. Without this git waits for a
        // username on a repository whose credentials the deployment did not provide — and waits
        // out the whole timeout below instead of saying so in a second.
        builder.environment().put("GIT_TERMINAL_PROMPT", "0");
        // The same for ssh, which asks on its own tty rather than through git. Only when the
        // deployment configured no command of its own: overriding that would undo the very
        // credentials this subprocess exists to reuse.
        builder.environment()
                .putIfAbsent("GIT_SSH_COMMAND", "ssh -o BatchMode=yes -o StrictHostKeyChecking=no");

        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot run git " + name, e);
        }
        // Read on a thread of our own, and wait on the process rather than on end-of-output: a
        // git that hangs holds its pipe open, and a read that owned the waiting would sit there
        // long past the timeout it is supposed to enforce.
        var reading =
                CompletableFuture.supplyAsync(
                        () -> {
                            try (var reader =
                                    new BufferedReader(
                                            new InputStreamReader(
                                                    process.getInputStream(),
                                                    StandardCharsets.UTF_8))) {
                                return truncate(reader.lines().toList());
                            } catch (IOException e) {
                                log.warn("Cannot read the output of git {}", name, e);
                                return "";
                            }
                        });
        try {
            // Nothing will ever be written to it, and a git left waiting on its stdin is a git
            // that never exits.
            process.getOutputStream().close();
        } catch (IOException e) {
            log.debug("Cannot close the stdin of git {}", name, e);
        }
        try {
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new GitCommandFailedException(
                        "git " + name + " did not finish in " + TIMEOUT_SECONDS + "s");
            }
            String output = reading.get(OUTPUT_DRAIN_SECONDS, TimeUnit.SECONDS);
            int exit = process.exitValue();
            if (exit != 0) {
                log.warn("git {} exited {} in {}: {}", name, exit, root, output);
                throw new GitCommandFailedException(
                        output.isBlank() ? "git " + name + " failed (exit " + exit + ")" : output);
            }
            return output;
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("Cannot read the output of git " + name, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("git " + name + " was interrupted", e);
        } finally {
            process.destroy();
            reading.cancel(true);
        }
    }

    private static String truncate(List<String> lines) {
        String text = String.join("\n", lines).strip();
        return text.length() <= MAX_OUTPUT_CHARS
                ? text
                : text.substring(0, MAX_OUTPUT_CHARS) + "\n… (output truncated)";
    }
}
