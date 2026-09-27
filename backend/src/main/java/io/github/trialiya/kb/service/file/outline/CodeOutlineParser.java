package io.github.trialiya.kb.service.file.outline;

import io.github.trialiya.kb.model.git.dto.GitSymbol;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Extracts the declared symbols (classes, methods, functions, tables, headings, ...) of a file.
 * Implementations may use a real grammar (tree-sitter) or a lightweight regex fallback. Callers
 * should treat the result as best-effort structural metadata, not a guarantee.
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
     * malformed input.
     *
     * @param language canonical language id
     * @param source full file content
     * @return symbols, empty when the file declares none; {@code null} when this parser could not
     *     read the file at all — the caller may then try another one
     */
    @Nullable List<GitSymbol> parse(String language, String source);
}
