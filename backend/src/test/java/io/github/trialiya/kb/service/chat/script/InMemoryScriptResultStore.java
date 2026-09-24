package io.github.trialiya.kb.service.chat.script;

import io.github.trialiya.kb.model.script.StoredScriptResult;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import org.jspecify.annotations.Nullable;

/**
 * {@link ScriptResultStore} over a map, for tests that stand a {@link ScriptRunner} up without a
 * database. Numbers results per chat the way {@link ChatScriptResults} does and reads ids through
 * its own parser, so what a sandbox test learns about ids holds for the real store too.
 */
public final class InMemoryScriptResultStore implements ScriptResultStore {

    private final Map<String, Map<Integer, Entry>> chats = new LinkedHashMap<>();

    private record Entry(StoredScriptResult summary, String json) {}

    @Override
    public synchronized Kept keep(
            String conversationId, @Nullable String script, String project, String json) {
        Map<Integer, Entry> chat =
                chats.computeIfAbsent(conversationId, c -> new LinkedHashMap<>());
        int seq = chat.size() + 1;
        String id = ChatScriptResults.idOf(seq);
        chat.put(
                seq,
                new Entry(
                        new StoredScriptResult(
                                id, script, project, json.length(), LocalDateTime.now()),
                        json));
        return Kept.as(id);
    }

    @Override
    public synchronized Optional<String> valueJson(String conversationId, String id) {
        OptionalInt seq = ChatScriptResults.seqOf(id);
        if (seq.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(chats.getOrDefault(conversationId, Map.of()).get(seq.getAsInt()))
                .map(Entry::json);
    }

    @Override
    public synchronized List<StoredScriptResult> list(String conversationId) {
        List<StoredScriptResult> list = new ArrayList<>();
        chats.getOrDefault(conversationId, Map.of()).values().forEach(e -> list.add(e.summary()));
        return list;
    }
}
