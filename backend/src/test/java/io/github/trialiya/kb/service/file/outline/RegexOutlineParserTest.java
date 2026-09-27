package io.github.trialiya.kb.service.file.outline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import io.github.trialiya.kb.model.git.dto.GitSymbol;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Фолбэк без нативного слоя: построчные шаблоны. */
class RegexOutlineParserTest {

    private final RegexOutlineParser parser = new RegexOutlineParser();

    private static List<String> kindsAndNames(List<GitSymbol> symbols) {
        return symbols.stream().map(s -> s.kind() + " " + s.name()).toList();
    }

    @Test
    void javaTypesAndMethodsButNotControlFlow() {
        String src =
                """
                public final class Repo {
                    public static <T> Map<String, List<T>> index(List<T> items) throws IOException {
                        if (items.isEmpty()) {
                        }
                        return null;
                    }
                    private   void   spaced ( int a ) {
                    }
                }
                """;
        assertEquals(
                List.of("class Repo", "method index", "method spaced"),
                kindsAndNames(parser.parse("java", src)));
    }

    /**
     * Длинный пробельный пробег в строке без объявления: шаблон, где модификаторы, тип и отступ
     * перед именем делят одни пробелы, перебирал бы их минутами.
     */
    @Test
    void aLongRunOfWhitespaceIsMatchedInLinearTime() {
        String line = "    int" + " ".repeat(5_000) + "x (" + " ".repeat(5_000) + ";\n";

        List<GitSymbol> symbols =
                assertTimeoutPreemptively(
                        Duration.ofSeconds(2), () -> parser.parse("java", line.repeat(20)));

        assertEquals(List.of(), symbols);
    }

    @Test
    void pythonIndentedDefIsAMethod() {
        String src =
                """
                class A:
                    async def load(self):
                        pass

                def main():
                    pass
                """;
        assertEquals(
                List.of("class A", "method load", "function main"),
                kindsAndNames(parser.parse("python", src)));
    }

    @Test
    void javascriptFunctionsClassesAndArrows() {
        String src =
                """
                export class Store {}
                export async function* stream(a) {}
                export const sum = (a: number, b: number): number => a + b;
                const notAFunction = compute(1);
                """;
        assertEquals(
                List.of("class Store", "function stream", "function sum"),
                kindsAndNames(parser.parse("typescript", src)));
    }

    @Test
    void sqlObjectsWithTheirKind() {
        String src =
                """
                CREATE TABLE IF NOT EXISTS public.users (id bigint);
                create or replace view active_users as select 1;
                CREATE INDEX users_idx ON users (id);
                """;
        assertEquals(
                List.of("table public.users", "view active_users", "index users_idx"),
                kindsAndNames(parser.parse("sql", src)));
    }
}
