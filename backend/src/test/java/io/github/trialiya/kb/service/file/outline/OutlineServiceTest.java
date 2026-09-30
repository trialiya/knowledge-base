package io.github.trialiya.kb.service.file.outline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.trialiya.kb.model.git.dto.GitSymbol;
import io.github.trialiya.kb.model.git.dto.OutlineResult;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** Какой разборщик отвечает за файл — и когда ответ одного передаётся следующему. */
class OutlineServiceTest {

    private static final GitSymbol FROM_REGEX = new GitSymbol("function", "r", "r()", 1, 1);

    /** Разборщик с заданным ответом: {@code null} — «не смог прочитать файл». */
    private static CodeOutlineParser answering(String name, @Nullable List<GitSymbol> answer) {
        return new CodeOutlineParser() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public boolean supports(@Nullable String language) {
                return "javascript".equals(language);
            }

            @Override
            public @Nullable List<GitSymbol> parse(String language, String source) {
                return answer;
            }
        };
    }

    private static OutlineResult outline(@Nullable List<GitSymbol> fromTreeSitter) {
        return new OutlineService(answering("tree-sitter", fromTreeSitter), answering("regex", List.of(FROM_REGEX)))
                .outline("javascript", "x");
    }

    @Test
    void treeSitterAnswersWhenItReadTheFile() {
        GitSymbol symbol = new GitSymbol("function", "t", "t()", 1, 1);

        assertEquals(new OutlineResult("tree-sitter", List.of(symbol)), outline(List.of(symbol)));
    }

    /**
     * Пустой ответ tree-sitter — тоже ответ: файл прочитан, объявлений нет. Regex добавил бы только
     * то, что tree-sitter не показывает намеренно, — колбэки внутри функций.
     */
    @Test
    void anEmptyTreeSitterAnswerIsKept() {
        assertEquals(new OutlineResult("tree-sitter", List.of()), outline(List.of()));
    }

    @Test
    void regexStepsInWhenTreeSitterCouldNotReadTheFile() {
        assertEquals(new OutlineResult("regex", List.of(FROM_REGEX)), outline(null));
    }

    @Test
    void markdownGoesToTheSectionParser() {
        OutlineService service = new OutlineService();

        assertTrue(service.isLanguageSupported(LanguageDetector.detect("docs/README.md")));
        assertFalse(service.isLanguageSupported(LanguageDetector.detect("notes.txt")));
        assertEquals(
                new OutlineResult("markdown", List.of(new GitSymbol("h1", "A", "A", 1, 1))),
                service.outline("markdown", "# A\n"));
    }
}
