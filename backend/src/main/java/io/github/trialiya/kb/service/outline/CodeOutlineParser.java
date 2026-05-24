package io.github.trialiya.kb.service.outline;

import io.github.trialiya.kb.model.git.dto.GitSymbol;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Extracts a list of top-level symbols (classes, methods, functions, fields, imports, tables, ...)
 * from source code. Implementations may use a real grammar (tree-sitter) or a lightweight regex
 * fallback. Callers should treat the result as best-effort structural metadata, not a guarantee.
 */
public interface CodeOutlineParser {

    /**
     * @return short identifier of the parsing strategy, e.g. {@code "tree-sitter"} or {@code
     *     "regex"} — surfaced to the AI so it knows how reliable the outline is.
     */
    String name();

    /**
     * @param language canonical language id (see {@link LanguageDetector})
     * @return {@code true} if this parser can extract symbols for the given language
     */
    boolean supports(@Nullable String language);

    /**
     * Parses {@code source} and returns its symbols in document order. Must never throw on
     * malformed input — return an empty list instead.
     *
     * @param language canonical language id
     * @param source full file content
     * @return symbols, possibly empty
     */
    List<GitSymbol> parse(String language, String source);
}
