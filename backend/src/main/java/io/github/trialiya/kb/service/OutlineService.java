package io.github.trialiya.kb.service;

import io.github.trialiya.kb.model.git.dto.GitSymbol;
import io.github.trialiya.kb.model.git.dto.OutlineResult;
import io.github.trialiya.kb.service.outline.CodeOutlineParser;
import io.github.trialiya.kb.service.outline.RegexOutlineParser;
import io.github.trialiya.kb.service.outline.TreeSitterOutlineParser;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Produces a file outline using the best available parser: tree-sitter when its native layer loaded
 * and supports the language, otherwise a regex fallback. Chosen-parser name is exposed so callers
 * can report it to the AI.
 *
 * <p>Distinguishes two "cannot outline" cases: a language outside the supported set (callers should
 * reject) versus a supported language whose tree-sitter native layer failed to load (transparent
 * regex fallback — the language IS supported, only the engine degraded).
 */
@Component
public class OutlineService {

    /** Languages for which an outline is meaningful at all. */
    private static final Set<String> SUPPORTED_LANGUAGES =
            Set.of("java", "javascript", "typescript", "python", "sql");

    private final CodeOutlineParser treeSitter;
    private final CodeOutlineParser regex;

    public OutlineService() {
        this(new TreeSitterOutlineParser(), new RegexOutlineParser());
    }

    /** Visible for testing. */
    OutlineService(CodeOutlineParser treeSitter, CodeOutlineParser regex) {
        this.treeSitter = treeSitter;
        this.regex = regex;
    }

    /**
     * @return whether the language is in the outline-supported set. {@code false} means the caller
     *     should surface an "unsupported language" error rather than an empty outline.
     */
    public boolean isLanguageSupported(@Nullable String language) {
        return language != null && SUPPORTED_LANGUAGES.contains(language);
    }

    /**
     * Extracts symbols for a <b>supported</b> language. Never throws; callers must gate on {@link
     * #isLanguageSupported} first. For a supported language whose tree-sitter engine is
     * unavailable, falls back to regex (parser {@code "regex"}).
     *
     * @param language canonical language id, expected to be supported
     * @param source full file content
     */
    public OutlineResult outline(String language, String source) {
        if (treeSitter.supports(language)) {
            List<GitSymbol> symbols = treeSitter.parse(language, source);
            // If tree-sitter yielded nothing (e.g. parse hiccup), try regex before giving up.
            if (!symbols.isEmpty()) {
                return new OutlineResult(treeSitter.name(), symbols);
            }
        }
        if (regex.supports(language)) {
            return new OutlineResult(regex.name(), regex.parse(language, source));
        }
        return new OutlineResult("none", List.of());
    }
}
