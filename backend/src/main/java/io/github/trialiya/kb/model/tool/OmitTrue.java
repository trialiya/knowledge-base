package io.github.trialiya.kb.model.tool;

import org.jspecify.annotations.Nullable;

/**
 * {@code @JsonInclude(value = CUSTOM, valueFilter = OmitTrue.class)}: Jackson leaves a property out
 * when this filter {@code equals} its value — here, when the value is {@code true}.
 *
 * <p>For a flag that is {@code true} almost always and matters only when {@code false}, like a
 * file's {@code tracked}: on a tree of five hundred nodes it is five hundred times the same word,
 * while the one untracked file still says so. A reader takes an absent flag as {@code true}; the
 * frontend's checks already read it that way ({@code tracked === false}).
 */
public final class OmitTrue {

    @Override
    public boolean equals(@Nullable Object value) {
        return Boolean.TRUE.equals(value);
    }

    @Override
    public int hashCode() {
        return Boolean.TRUE.hashCode();
    }
}
