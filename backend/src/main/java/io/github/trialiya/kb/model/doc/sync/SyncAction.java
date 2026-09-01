package io.github.trialiya.kb.model.doc.sync;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/**
 * What an import actually did to one node.
 *
 * <p>{@link SyncStatus} is the comparison's verdict — what <em>would</em> happen. This is the
 * import's receipt: what did. They differ more often than one would like (a node compared {@code
 * ADDED} but its title collided with a system node, a body was written and then rewritten by the
 * relink pass), and only the second half answers "what just happened to my knowledge base".
 */
public enum SyncAction {

    /** A node that did not exist was created. */
    CREATED,

    /** An existing node's body — and possibly its title — was overwritten from the file. */
    UPDATED,

    /** A node with no file behind it was removed, together with its subtree. */
    DELETED,

    /** Second pass: the node's relative links were turned back into {@code /?doc=ID}. */
    RELINKED,

    /** The node was skipped; the event's {@code message} carries the reason. */
    FAILED;

    @JsonValue
    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }
}
