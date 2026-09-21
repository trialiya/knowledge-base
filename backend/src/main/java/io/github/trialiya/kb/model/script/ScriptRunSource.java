package io.github.trialiya.kb.model.script;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Where the script of one run came from, when it was not written in the call itself.
 *
 * <p>Reported back for two readers. The model needs it because {@code filesRead}, {@code edits} and
 * the error line belong to a text it never saw. The human needs it in the tool-call detail, where
 * it replaces a wall of embedded source with a path that opens in the Files panel — and where
 * {@link #sha} answers the only question a later reader cannot otherwise ask: the file has moved on
 * since, so <em>which</em> text was this?
 *
 * @param kind which shelf the script came off
 * @param name the name it was called by
 * @param path repository path of the file (a chat or document attachment: its file name)
 * @param sha short SHA-256 of the exact text that ran
 * @param args the arguments it ran with, after defaults and coercion — what the script saw, not
 *     what the caller typed
 */
public record ScriptRunSource(
        Kind kind, String name, String path, String sha, Map<String, Object> args) {

    public ScriptRunSource {
        args = args == null ? Map.of() : new LinkedHashMap<>(args);
    }

    public enum Kind {
        /** A file of the active project, declared in its script manifest. */
        PROJECT,
        /** An attachment of the chat or of a document — always read-only. */
        ATTACHMENT
    }
}
