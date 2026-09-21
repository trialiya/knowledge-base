package io.github.trialiya.kb.model.script;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * One argument a saved script declares — a {@code params[]} entry of the project's script manifest
 * (see {@code ScriptManifestReader}).
 *
 * <p>The declaration has three readers, and that is why it exists at all rather than being left to
 * the script's own code: the model reads it as the catalogue line that says what may be passed,
 * {@code ScriptArgs} checks a call against it <em>before</em> the run starts, and the human
 * surfaces (the settings bench, later a chat command) build their input from it. A script that
 * validated its own arguments could serve only the first of the three, and only after the run had
 * already begun.
 *
 * @param name key in the {@code args} object the tool is called with
 * @param desc one line for the catalogue; may be empty when the name says everything
 * @param type what the value has to be — checked, and for a scalar coerced, by {@code ScriptArgs}
 * @param required refuse the call when it is absent; mutually exclusive with {@link #defaultValue}
 * @param defaultValue substituted when the key is absent altogether, never when it is present and
 *     null — an explicit null is a value the script asked to see
 */
public record ScriptParam(
        String name, String desc, Type type, boolean required, @Nullable Object defaultValue) {

    /**
     * The value shapes a declaration can ask for. Deliberately shallow — no element type for a
     * list, no shape for an object: a script is not an endpoint, and a schema deep enough to be
     * worth writing would cost more to maintain than the failures it prevents.
     */
    public enum Type {
        STRING,
        NUMBER,
        BOOLEAN,
        ARRAY,
        OBJECT;

        /** The manifest spelling of a type; unknown text is the manifest's error, not a default. */
        public static Type parse(String text) {
            try {
                return valueOf(text.strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "unknown param type \""
                                + text
                                + "\" — one of string, number, boolean, array, object",
                        e);
            }
        }
    }
}
