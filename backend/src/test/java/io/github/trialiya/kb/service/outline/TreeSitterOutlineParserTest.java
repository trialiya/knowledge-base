package io.github.trialiya.kb.service.outline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.trialiya.kb.model.git.dto.GitSymbol;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * End-to-end tests for {@link TreeSitterOutlineParser} against the real tree-sitter Java grammar.
 *
 * <p>These verify the signature-building behaviour that previously regressed:
 *
 * <ul>
 *   <li>method parameters appear in the signature (not empty parens),
 *   <li>access modifiers appear (public/private/…),
 *   <li>annotations — including ones with multi-byte (Cyrillic) arguments — never leak into the
 *       signature,
 *   <li>start line points at the declaration, not a leading annotation.
 * </ul>
 *
 * <p>If the tree-sitter native library is not available on the test platform, the parser reports
 * {@code supports(...) == false}; these tests then skip via {@link Assumptions} rather than fail,
 * because they specifically target the tree-sitter path.
 */
class TreeSitterOutlineParserTest {

    private TreeSitterOutlineParser parser;

    @BeforeEach
    void setUp() {
        parser = new TreeSitterOutlineParser();
        Assumptions.assumeTrue(
                parser.supports("java"),
                "tree-sitter native library unavailable on this platform; skipping");
    }

    private static Stream<GitSymbol> find(List<GitSymbol> symbols, String name) {
        return symbols.stream().filter(s -> s.name().equals(name));
    }

    private static boolean isAscii(String s) {
        return s.chars().allMatch(c -> c < 128);
    }

    @Test
    void methodWithMultilineParamsAndCyrillicAnnotation() {
        String src =
                """
                package com.example;

                import java.util.List;
                import lombok.extern.slf4j.Slf4j;

                @Slf4j
                public class GitFunction {

                    @Tool(description = "Получить дерево файлов")
                    public List<GitFileNode> getFileTree(
                            @ToolParam(description = "Путь к подкаталогу (например, \\"src/main/java\\")")
                                    String path) {
                        return null;
                    }
                }
                """;

        List<GitSymbol> symbols = parser.parse("java", src);

        GitSymbol cls = find(symbols, "GitFunction").findFirst().orElseThrow();
        assertEquals("class", cls.kind());
        assertEquals("public class GitFunction", cls.signature());
        // class declaration line, not the @Slf4j line
        assertTrue(cls.signature().startsWith("public class"));

        GitSymbol m = find(symbols, "getFileTree").findFirst().orElseThrow();
        assertEquals("method", m.kind());
        assertEquals("public List<GitFileNode> getFileTree(String path)", m.signature());
        assertTrue(isAscii(m.signature()), "signature must not contain annotation text");
        assertFalse(m.signature().contains("()"), "param must be present");
        assertFalse(m.signature().contains("ToolParam"));
    }

    @Test
    void methodWithMultipleParams() {
        String src =
                """
                public class A {
                    @Tool(description = "История")
                    public List<GitCommit> getCommitLog(
                            @ToolParam(description = "Максимум 1–100") Integer maxCount,
                            @ToolParam(description = "Путь к файлу") String filePath) {
                        return null;
                    }
                }
                """;

        GitSymbol m = find(parser.parse("java", src), "getCommitLog").findFirst().orElseThrow();
        assertEquals(
                "public List<GitCommit> getCommitLog(Integer maxCount, String filePath)",
                m.signature());
        assertTrue(isAscii(m.signature()));
    }

    @Test
    void noArgMethodHasEmptyParens() {
        String src =
                """
                public class A {
                    private int count() { return 0; }
                }
                """;
        GitSymbol m = find(parser.parse("java", src), "count").findFirst().orElseThrow();
        assertEquals("private int count()", m.signature());
    }

    @Test
    void constructorHasNoReturnType() {
        String src =
                """
                public class GitService {
                    public GitService(String projectPath, OutlineService outlineService) {
                    }
                }
                """;
        GitSymbol c =
                find(parser.parse("java", src), "GitService")
                        .filter(s -> s.kind().equals("constructor"))
                        .findFirst()
                        .orElseThrow();
        assertEquals(
                "public GitService(String projectPath, OutlineService outlineService)",
                c.signature());
    }

    @Test
    void keywordInsideAnnotationStringDoesNotLeak() {
        String src =
                """
                public class A {
                    @Tool(description = "use the public API here")
                    private void foo() {}
                }
                """;
        GitSymbol m = find(parser.parse("java", src), "foo").findFirst().orElseThrow();
        // "public" appears only inside the annotation string, must not appear as a modifier
        assertEquals("private void foo()", m.signature());
    }

    @Test
    void startLinePointsAtDeclarationNotAnnotation() {
        String src =
                """
                public class A {

                    @Tool(description = "x")
                    @Deprecated
                    public void bar() {}
                }
                """;
        // bar() is declared on line 5 (1-based); annotations are on lines 3-4.
        GitSymbol m = find(parser.parse("java", src), "bar").findFirst().orElseThrow();
        assertEquals(5, m.startLine());
    }

    @Test
    void pythonClassAndFunction() {
        Assumptions.assumeTrue(parser.supports("python"));
        String src =
                """
                class Animal:
                    def speak(self):
                        return "hi"

                def helper():
                    return 1
                """;
        List<GitSymbol> symbols = parser.parse("python", src);
        assertNotNull(find(symbols, "Animal").findFirst().orElse(null));
        assertNotNull(find(symbols, "speak").findFirst().orElse(null));
        assertNotNull(find(symbols, "helper").findFirst().orElse(null));
    }
}
