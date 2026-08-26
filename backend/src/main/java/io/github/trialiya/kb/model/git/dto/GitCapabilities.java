package io.github.trialiya.kb.model.git.dto;

/**
 * What the UI may offer a user for one repository — the answer behind whether the files panel shows
 * git controls at all, and whether {@code push} is among them.
 *
 * <p>Asked per request rather than carried in {@code ProjectView}: the project list is cached by
 * the client, and every field here can change under a running server — a mount remounted read-only
 * revokes the commands, and a repository that failed to open never had them.
 *
 * <p>Nothing here is a model capability. These commands are run by a person clicking in the
 * interface; the assistant has no tool for any of them.
 *
 * @param project id of the project the answer is about — the resolved one, so a caller that named
 *     nothing learns which repository it got
 * @param available whether this project's repository opened at startup; false makes the rest false
 *     too, and tells the panel to say the project is unavailable rather than that git is off for it
 * @param commands whether the local commands and the network reads are permitted: branch/status
 *     reads, switch, stash, commit, discard, {@code merge --abort}, fetch, pull
 * @param push whether {@code push} is permitted as well — always false when {@link #commands()} is
 */
public record GitCapabilities(String project, boolean available, boolean commands, boolean push) {}
