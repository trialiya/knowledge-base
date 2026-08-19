package io.github.trialiya.kb.tools;

import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.model.ToolContext;

/**
 * Which project a tool call works on, carried through the {@link ToolContext} beside the
 * conversation id, the user and the run's stop flag (see {@code ChatUtils#context}).
 *
 * <p>The context — not a tool argument — because the project is a property of the run, not a
 * decision the model makes on each call: one wrong id in one generated argument would silently read
 * a different repository. It also stays out of the tool schema, so the model is not asked to fill
 * in something it has no way to know.
 *
 * <p>The key comes from the chat's own project ({@code chat_topic.project}, resolved by {@code
 * ChatController#resolveProject}); the search sub-agent passes on whatever its parent run carried.
 * Absent means "the caller does not know" — a chat that never chose, a summary, a background job —
 * which {@code GitRegistry} answers with the default project.
 */
public final class ProjectContext {

    public static final String KEY = "projectId";

    private ProjectContext() {}

    /** The project id in {@code context}, or {@code null} — "not stated, use the default". */
    public static @Nullable String from(@Nullable ToolContext context) {
        if (context == null) {
            return null;
        }
        return Optional.ofNullable(context.getContext().get(KEY))
                .map(Object::toString)
                .filter(id -> !id.isBlank())
                .orElse(null);
    }
}
