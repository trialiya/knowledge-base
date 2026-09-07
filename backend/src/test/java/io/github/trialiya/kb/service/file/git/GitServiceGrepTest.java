package io.github.trialiya.kb.service.file.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.trialiya.kb.model.git.dto.GitGrepMatch;
import io.github.trialiya.kb.support.TestProjects;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Поиск по содержимому через настоящий {@code git grep}: рабочее дерево против произвольной
 * ревизии, и как ошибки самого git доходят до вызывающего.
 */
class GitServiceGrepTest {

    @TempDir Path repoDir;

    private GitService service;

    @BeforeEach
    void setUp() {
        runGit("init", "-q");
        runGit("config", "user.email", "test@example.com");
        runGit("config", "user.name", "Test");
        service = TestProjects.gitService(repoDir, false);
    }

    /**
     * Строка, которая была в первом коммите и исчезла из рабочего дерева, находится по ревизии и не
     * находится по индексу — то есть ревизия действительно ушла в git, а префикс {@code <sha>:} из
     * вывода снят, раз пути разобрались.
     */
    @Test
    void grepAtARevisionSearchesThatCommitNotTheWorkingTree() {
        writeFile("src/App.java", "class App {\n  // needle in the first commit\n}\n");
        commitAll("first");
        writeFile("src/App.java", "class App {\n}\n");
        commitAll("second");

        List<GitGrepMatch> atFirst = service.grepContentAt("HEAD~1", "needle", null, false, 0, 50);
        List<GitGrepMatch> now = service.grepContent("needle", null, false, 0, 50, false);

        assertThat(atFirst)
                .singleElement()
                .satisfies(
                        m -> {
                            assertThat(m.path()).isEqualTo("src/App.java");
                            assertThat(m.matchLine()).isEqualTo(2);
                        });
        assertThat(now).isEmpty();
    }

    @Test
    void grepAtARevisionHonoursThePathGlob() {
        writeFile("src/A.java", "needle\n");
        writeFile("docs/A.md", "needle\n");
        commitAll("first");

        List<GitGrepMatch> matches =
                service.grepContentAt("HEAD", "needle", "docs/*", false, 0, 50);

        assertThat(matches).extracting(GitGrepMatch::path).containsExactly("docs/A.md");
    }

    @Test
    void anUnknownRevisionIsTheCallersMistake() {
        writeFile("a.txt", "needle\n");
        commitAll("first");

        assertThatThrownBy(
                        () -> service.grepContentAt("no-such-branch", "needle", null, false, 0, 50))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Регулярное выражение разбирает git, не мы: его отказ (exit 128) приходит как {@link
     * IllegalArgumentException} с текстом самого git, а не как сбой сервиса.
     */
    @Test
    void aBrokenRegexIsReportedAsABadArgumentWithGitsOwnWording() {
        writeFile("a.txt", "needle\n");
        commitAll("first");

        assertThatThrownBy(() -> service.grepContent("needle(", null, true, 0, 50, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("'needle('");
    }

    private void writeFile(String relativePath, String content) {
        try {
            Path file = repoDir.resolve(relativePath);
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void commitAll(String message) {
        runGit("add", "-A");
        runGit("commit", "-q", "-m", message);
    }

    private void runGit(String... args) {
        try {
            List<String> command = new ArrayList<>();
            command.add("git");
            command.addAll(List.of(args));
            Process process =
                    new ProcessBuilder(command)
                            .directory(repoDir.toFile())
                            .redirectErrorStream(true)
                            .start();
            String output =
                    new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (process.waitFor() != 0) {
                throw new IllegalStateException("git " + String.join(" ", args) + ": " + output);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
