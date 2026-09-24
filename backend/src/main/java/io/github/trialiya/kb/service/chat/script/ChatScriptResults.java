package io.github.trialiya.kb.service.chat.script;

import io.github.trialiya.kb.config.model.ScriptResultProperties;
import io.github.trialiya.kb.model.chat.entity.ChatScriptResultEntity;
import io.github.trialiya.kb.model.script.StoredScriptResult;
import io.github.trialiya.kb.repository.ChatScriptResultRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * Script results kept per chat in {@code chat_script_result}, numbered {@code r1, r2, …} within the
 * chat.
 *
 * <p>A number per chat rather than the row id: the model reads and repeats it, and a short number
 * that starts at one in every chat is both harder to get wrong and says nothing about other chats.
 * Two runs of one chat do not normally overlap — a chat runs one answer or one command at a time —
 * but the unique key would still catch it, and the loser takes the next number.
 *
 * <p>{@link #keep} must not be called inside an open transaction: on Postgres the duplicate key it
 * recovers from would mark that transaction rollback-only. Its callers are the script runs, which
 * hold none; every repository call here is a transaction of its own.
 */
@AllArgsConstructor
@Slf4j
@Service
public class ChatScriptResults implements ScriptResultStore {

    /** Attempts at a free number before giving up on keeping one value. */
    private static final int SEQ_ATTEMPTS = 3;

    private final ChatScriptResultRepository repository;
    private final ScriptResultProperties properties;

    @Override
    public Kept keep(String conversationId, @Nullable String script, String project, String json) {
        if (!properties.enabled()) {
            return Kept.not(null);
        }
        if (json.length() > properties.maxChars()) {
            return Kept.not(
                    "Result not kept: it is "
                            + json.length()
                            + " characters, over kb.script.results.max-chars="
                            + properties.maxChars()
                            + ", so no later script can read it with kb.result. Return less.");
        }
        final int seq;
        try {
            seq = insert(conversationId, script, project, json);
        } catch (RuntimeException e) {
            // A chat that has no row yet (a run outside any saved conversation) lands here on the
            // foreign key, and so does a database or pool failure. Either way the run itself
            // succeeded — and may already have written files — so it must not turn into an error.
            log.warn("Script result of chat {} was not kept", conversationId, e);
            return Kept.not(null);
        }
        if (seq > properties.keepPerChat()) {
            try {
                repository.deleteUpTo(conversationId, seq - properties.keepPerChat());
            } catch (RuntimeException e) {
                // The new row is in; failing to drop old ones only delays that until the next.
                log.warn("Old script results of chat {} were not dropped", conversationId, e);
            }
        }
        return Kept.as(idOf(seq));
    }

    private int insert(
            String conversationId, @Nullable String script, String project, String json) {
        for (int attempt = 1; ; attempt++) {
            final int seq = repository.maxSeq(conversationId) + 1;
            try {
                repository.save(
                        new ChatScriptResultEntity(
                                0L,
                                conversationId,
                                seq,
                                script,
                                project,
                                json,
                                json.length(),
                                LocalDateTime.now()));
                return seq;
            } catch (DuplicateKeyException e) {
                if (attempt >= SEQ_ATTEMPTS) {
                    throw e;
                }
            }
        }
    }

    @Override
    public Optional<String> valueJson(String conversationId, String id) {
        final OptionalInt seq = seqOf(id);
        if (!properties.enabled() || seq.isEmpty()) {
            return Optional.empty();
        }
        return repository
                .findByConversationIdAndSeq(conversationId, seq.getAsInt())
                .map(ChatScriptResultEntity::getValueJson);
    }

    @Override
    public List<StoredScriptResult> list(String conversationId) {
        if (!properties.enabled()) {
            return List.of();
        }
        return repository.listWithoutValues(conversationId).stream()
                .map(
                        row ->
                                new StoredScriptResult(
                                        idOf(row.getSeq()),
                                        row.getScript(),
                                        row.getProject(),
                                        row.getChars(),
                                        row.getCreatedAt()))
                .toList();
    }

    @Override
    public boolean enabled() {
        return properties.enabled();
    }

    static String idOf(int seq) {
        return "r" + seq;
    }

    /**
     * The number behind an id. Lenient on purpose: {@code "r3"}, {@code "R3"} and a bare {@code
     * "3"} all mean the same result — a weak model drops the prefix as readily as it keeps it, and
     * there is nothing else the digits could mean here.
     */
    static OptionalInt seqOf(String id) {
        String text = id.strip().toLowerCase(Locale.ROOT);
        if (text.startsWith("r")) {
            text = text.substring(1);
        }
        if (text.isEmpty()
                || text.length() > 9
                || !text.chars().allMatch(c -> c >= '0' && c <= '9')) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(Integer.parseInt(text));
    }
}
