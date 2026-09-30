package io.github.trialiya.kb.service.file.outline;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.trialiya.kb.model.git.dto.GitSymbol;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * End-to-end tests for {@link TreeSitterOutlineParser} against the real tree-sitter grammars: which
 * nodes become symbols, of what kind, with what signature and line range.
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
                parser.supports("java"), "tree-sitter native library unavailable on this platform; skipping");
    }

    private static Stream<GitSymbol> find(List<GitSymbol> symbols, String name) {
        return symbols.stream().filter(s -> s.name().equals(name));
    }

    private static List<String> names(List<GitSymbol> symbols) {
        return symbols.stream().map(GitSymbol::name).toList();
    }

    @Test
    void methodWithMultilineParamsAndCyrillicAnnotation() {
        String src = """
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

        GitSymbol m = find(symbols, "getFileTree").findFirst().orElseThrow();
        assertEquals("method", m.kind());
        // Neither the Cyrillic annotation arguments nor the annotations themselves.
        assertEquals("public List<GitFileNode> getFileTree(String path)", m.signature());
    }

    @Test
    void methodWithMultipleParams() {
        String src = """
                public class A {
                    @Tool(description = "История")
                    public List<GitCommit> getCommitLog(
                            @ToolParam(description = "Максимум 1–100") Integer maxCount,
                            @ToolParam(description = "Путь к файлу") String filePath) {
                        return null;
                    }
                }
                """;

        GitSymbol m =
                find(parser.parse("java", src), "getCommitLog").findFirst().orElseThrow();
        assertEquals("public List<GitCommit> getCommitLog(Integer maxCount, String filePath)", m.signature());
    }

    @Test
    void noArgMethodHasEmptyParens() {
        String src = """
                public class A {
                    private int count() { return 0; }
                }
                """;
        GitSymbol m = find(parser.parse("java", src), "count").findFirst().orElseThrow();
        assertEquals("private int count()", m.signature());
    }

    @Test
    void constructorHasNoReturnType() {
        String src = """
                public class GitService {
                    public GitService(String projectPath, OutlineService outlineService) {
                    }
                }
                """;
        GitSymbol c = find(parser.parse("java", src), "GitService")
                .filter(s -> s.kind().equals("constructor"))
                .findFirst()
                .orElseThrow();
        assertEquals("public GitService(String projectPath, OutlineService outlineService)", c.signature());
    }

    @Test
    void keywordInsideAnnotationStringDoesNotLeak() {
        String src = """
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
        String src = """
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

    /** {@code def} прямо в теле класса — метод; вложенная в функцию — её код, не структура. */
    @Test
    void pythonDefInAClassIsAMethodAndNestedDefsAreNotListed() {
        String src = """
                class Animal:
                    @property
                    def speak(self) -> str:
                        def inner():
                            pass
                        return "hi"

                def helper(a, b=1):
                    return a
                """;
        List<GitSymbol> symbols = parser.parse("python", src);

        assertEquals(
                List.of(
                        new GitSymbol("class", "Animal", "class Animal", 1, 6),
                        new GitSymbol("method", "speak", "def speak(self) -> str", 3, 6),
                        new GitSymbol("function", "helper", "def helper(a, b=1)", 8, 9)),
                symbols);
    }

    /** Компоненты React — стрелочные функции в модульных const, с разметкой JSX внутри. */
    @Test
    void jsxComponentsHeldInConstsAreFunctions() {
        String src = """
                import { useState } from 'react';

                const Row = ({ label }) => <li>{label}</li>;

                export const List = ({
                  items,
                  onPick,
                }) => {
                  const handleClick = (item) => onPick(item);
                  return <ul>{items.map((i) => <Row key={i} label={i} />)}</ul>;
                };

                export default function App() {
                  const [x] = useState(0);
                  return <List items={[x]} onPick={() => {}} />;
                }
                """;
        List<GitSymbol> symbols = parser.parse("javascript", src);

        assertEquals(List.of("Row", "List", "App"), names(symbols));
        GitSymbol list = symbols.get(1);
        assertEquals("function", list.kind());
        assertEquals(5, list.startLine());
        assertEquals(11, list.endLine());
        // Весь заголовок до тела, а не первая строка, оборванная на «({».
        assertEquals("export const List = ({ items, onPick, }) =>", list.signature());
        assertEquals("const Row = ({ label }) =>", symbols.get(0).signature());
    }

    /** Модульный объект функций (api-клиент) и поле класса со стрелочной функцией — методы. */
    @Test
    void functionsInModuleObjectsAndClassFieldsAreMethods() {
        String src = """
                const api = {
                  load: (id) => fetch(id),
                  save(doc) { return doc; },
                  limit: 10,
                };

                class Store {
                  refresh = async () => {
                    await api.load(1);
                  };
                }

                function outer() {
                  const local = { inner: () => 1 };
                  return local;
                }
                """;
        List<GitSymbol> symbols = parser.parse("javascript", src);

        assertEquals(List.of("load", "save", "Store", "refresh", "outer"), names(symbols));
        assertEquals("method", symbols.get(0).kind());
        assertEquals("load: (id) =>", symbols.get(0).signature());
        assertEquals("method", symbols.get(3).kind());
    }

    @Test
    void typescriptArrowFunctionKeepsItsTypes() {
        String src = """
                export const sum = (a: number, b: number): number => {
                  return a + b;
                };
                """;
        GitSymbol sum = parser.parse("typescript", src).get(0);

        assertEquals("function", sum.kind());
        assertEquals("export const sum = (a: number, b: number): number =>", sum.signature());
    }

    /** Декоратор — не часть сигнатуры: у TS он дочерний узел класса и метода. */
    @Test
    void typescriptDecoratorsStayOutOfTheSignature() {
        String src = """
                @Injectable({ providedIn: 'root' })
                export class Store {
                  @Input()
                  load(id: string): void {}
                }
                """;
        List<GitSymbol> symbols = parser.parse("typescript", src);

        assertEquals(
                "class Store", find(symbols, "Store").findFirst().orElseThrow().signature());
        GitSymbol load = find(symbols, "load").findFirst().orElseThrow();
        assertEquals("load(id: string): void", load.signature());
        assertEquals(4, load.startLine());
    }

    /**
     * Тело функции — её код: вложенная {@code function}, методы объекта-аргумента и анонимного
     * класса структурой файла не являются. Объект, переданный вызову, — тоже аргумент, а не модуль.
     */
    @Test
    void nothingInsideAFunctionBodyIsListed() {
        String js = """
                export function Comp() {
                  function handle() {}
                  return useMemo(() => ({ render() {} }), []);
                }
                register({ install() {} });
                export default { mounted() {} };
                """;
        assertEquals(List.of("Comp", "mounted"), names(parser.parse("javascript", js)));

        String java = """
                class A {
                    void run() {
                        new Thread(new Runnable() { public void run() {} }).start();
                        class Local {}
                    }
                }
                """;
        assertEquals(List.of("A", "run"), names(parser.parse("java", java)));
    }

    @Test
    void typescriptAbstractClassesEnumsAndTypeAliasesAreListed() {
        String src = """
                export abstract class Base {
                  abstract run(): void;
                }
                export enum Color { Red, Green }
                export type Id = string;
                """;
        assertEquals(
                List.of(
                        new GitSymbol("class", "Base", "abstract class Base", 1, 3),
                        new GitSymbol("method", "run", "abstract run(): void", 2, 2),
                        new GitSymbol("enum", "Color", "enum Color", 4, 4),
                        new GitSymbol("type", "Id", "type Id = string", 5, 5)),
                parser.parse("typescript", src));
    }

    @Test
    void javaSignaturesKeepTypeParametersAndThrows() {
        String src = """
                public @interface Marker {}
                record Range(int from, int to) {
                    Range {
                    }
                    static <T extends Comparable<T>> T max(T a, T b) throws IOException {
                        return a;
                    }
                }
                """;
        List<GitSymbol> symbols = parser.parse("java", src);

        assertEquals(
                List.of(
                        "annotation public @interface Marker",
                        "record record Range",
                        "constructor Range",
                        "method static <T extends Comparable<T>> T max(T a, T b) throws IOException"),
                symbols.stream().map(s -> s.kind() + " " + s.signature()).toList());
    }

    /**
     * Выражение из тысяч слагаемых вложено на тысячи уровней: обход рекурсией падал бы с
     * переполнением стека и терял весь файл, а не только это выражение.
     */
    @Test
    void aDeeplyNestedExpressionDoesNotCostTheRestOfTheFile() {
        String src = "const x = 1" + " + a".repeat(20_000) + ";\nfunction after() {}\n";

        assertEquals(List.of("after"), names(parser.parse("javascript", src)));
    }
}
