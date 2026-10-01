package io.github.trialiya.kb.model.tool;

/**
 * Jackson views for the JSON a tool answers the model with.
 *
 * <p>{@code CompactToolResultConverter} writes every tool result through {@link Model}; a property
 * marked {@code @JsonView(ToolJson.UiOnly.class)} is left out of that text and nowhere else. REST
 * controllers write with no active view, so the same DTO keeps the field for the UI — the reason
 * this is a view and not {@code @JsonIgnore}: a file tree node's {@code name} is what the Files tree
 * prints, and only repeats the last segment of {@code path} to the model.
 *
 * <p>The call's detail view shows the text the model got, so a {@code UiOnly} field is not there
 * either — only REST has it. A field the detail view must show is a job for {@link ModelView}
 * instead, which keeps the whole result for it.
 *
 * <p>A property without {@code @JsonView} is in every view.
 */
public final class ToolJson {

    private ToolJson() {}

    /** The text the model reads. */
    public interface Model {}

    /** For the UI only — REST, never the model's text. */
    public interface UiOnly {}
}
