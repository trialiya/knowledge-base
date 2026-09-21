package io.github.trialiya.kb.service.chat.script;

import io.github.trialiya.kb.config.model.ScriptProperties;
import io.github.trialiya.kb.model.chat.dto.ChatEventType;
import io.github.trialiya.kb.model.chat.dto.ScriptRunPayload;
import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import io.github.trialiya.kb.model.chat.entity.ScriptEventMeta;
import io.github.trialiya.kb.model.script.ScriptResult;
import io.github.trialiya.kb.service.chat.event.ChatEventService;
import io.github.trialiya.kb.service.chat.memory.ChatHistoryService;
import io.github.trialiya.kb.service.chat.run.RunOptionsResolver;
import io.github.trialiya.kb.service.chat.runtime.ChatActionClaim;
import io.github.trialiya.kb.tools.RunCancellation;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * A saved script the <b>user</b> runs from a chat with the {@code /script} command.
 *
 * <p>The same engine, the same catalogue and the same budgets the model's {@code runSavedScript}
 * goes through — the difference is who asked and what happens afterwards: the run leaves a row in
 * the history, so the answer is in the conversation rather than only on the screen of whoever typed
 * it, and the model reads it as a notice ({@code PromptNotices.scriptRunNotice}) instead of
 * learning nothing about a repository that moved under it.
 *
 * <p><b>Writes follow the project, not the surface.</b> Unlike the settings bench, this run may
 * edit files where {@code ScriptEditPolicy} allows it: the chat is exactly where a diff is shown
 * and attributed to a message, which is the reason the bench is read-only in the first place. What
 * such a run cannot be is undone by the answer-level revert — a script's edits never could be (see
 * {@code FileRevertPlan}), and the notice says as much to the model.
 *
 * <p>The chat is claimed for the length of the run, the way a git command claims it: the model must
 * not be reading and writing the same working tree at the same time, and the claim is taken rather
 * than checked so nothing can start in between ({@link ChatActionClaim}).
 */
@AllArgsConstructor
@Slf4j
@Service
public class ChatScriptRun {

    private final ScriptProperties properties;
    private final ChatActionClaim claim;
    private final RunOptionsResolver runOptions;
    private final SavedScriptResolver resolver;
    private final ScriptRunner scriptRunner;
    private final ScriptEditPolicy editPolicy;
    private final ChatHistoryService chatHistory;
    private final ChatEventService chatEvents;

    /**
     * Runs {@code name} in {@code conversationId}'s project and records what it did.
     *
     * @throws ResponseStatusException scripts are switched off, or the chat is not this user's,
     *     does not exist, or is busy — the same codes every other action on a chat answers with
     * @throws IllegalArgumentException the name or the arguments cannot be satisfied; the caller
     *     turns it into a 400, and nothing was run or recorded
     */
    public ScriptResult run(
            String conversationId,
            String name,
            @Nullable Map<String, Object> args,
            @Nullable Integer timeoutSeconds) {
        // Before the claim, and before anything is resolved: with the sandbox off there is no run
        // to hold the chat for. 409 rather than 404 — the command exists, the deployment turned
        // off what it needs, and that is the same answer the settings bench gives.
        if (!properties.enabled()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Scripts are disabled (kb.script.enabled=false)");
        }
        final String token = claim.claimIdleAndOwned(conversationId);
        try {
            // The chat's own project, resolved the way an operation on this chat resolves it: the
            // selector in the UI is not asked, because the command belongs to the conversation.
            final String projectId = runOptions.current(conversationId).canonicalProject();
            final ScriptRequest request =
                    resolver.resolve(
                            projectId,
                            name,
                            args,
                            timeoutSeconds,
                            editPolicy.enabled(projectId),
                            null);
            log.info(
                    "/script in chat {}: '{}' ({}), args={}, project='{}', readOnly={}",
                    conversationId,
                    name,
                    request.source().sourceName(),
                    request.args().values().keySet(),
                    projectId,
                    request.forceReadOnly());
            // No RunCancellation: there is no chat run to stop — the wall-clock budget is the only
            // limit, the same situation as the bench and the synchronous chat endpoint.
            final ScriptResult result = scriptRunner.run(request, RunCancellation.none());
            record(conversationId, name, result);
            return result;
        } finally {
            claim.release(conversationId, token);
        }
    }

    /**
     * Writes the row and tells the other tabs about it.
     *
     * <p>A failed run is recorded too, for the reason a refused git command is: it is the half the
     * user comes back to, and a model that never hears about it will keep assuming the script did
     * what its name says.
     *
     * <p>A failure of this write does not turn into a failed run: the script has already finished
     * and, if it wrote, the working tree has already moved. Answering with an error would make the
     * panel draw a state that no longer exists; the lost row is a loss, but a recoverable one.
     */
    private void record(String conversationId, String name, ScriptResult result) {
        final ScriptEventMeta event = ScriptEventMeta.of(name, result);
        try {
            final ChatMessageEntity row = chatHistory.appendScriptEvent(conversationId, event);
            chatEvents.publish(
                    conversationId,
                    ChatEventType.SCRIPT_RUN,
                    null,
                    null,
                    new ScriptRunPayload(row.getId(), row.getCreatedAt(), event));
        } catch (RuntimeException e) {
            log.warn("Failed to record script run {} in chat {}", name, conversationId, e);
        }
    }
}
