package io.github.trialiya.kb.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trialiya.kb.model.git.dto.GitFileNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link GitService#getFileTree(String)} against a real, throwaway git repository
 * (built via the {@code git} binary itself, mirroring how {@link GitService} shells out).
 */
class GitServiceTest {

    @TempDir Path repoDir;

    private GitService service;

    @BeforeEach
    void setUp() {
        runGit("init", "-q");
        runGit("config", "user.email", "test@example.com");
        runGit("config", "user.name", "Test");
        service = new GitService(repoDir.toString(), new OutlineService());
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

    private void commitAll() {
        runGit("add", "-A");
        runGit("commit", "-q", "-m", "test commit");
    }

    private void runGit(String... args) {
        try {
            var command = new java.util.ArrayList<String>();
            command.add("git");
            command.addAll(List.of(args));
            Process process =
                    new ProcessBuilder(command)
                            .directory(repoDir.toFile())
                            .redirectErrorStream(true)
                            .start();
            String output =
                    new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exit = process.waitFor();
            if (exit != 0) {
                throw new IllegalStateException(
                        "git " + String.join(" ", args) + " failed: " + output);
            }
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Failed to run git command", e);
        }
    }

    @Test
    void parsesCyrillicPathsAsOneNodeWithoutPhantomEntries() {
        writeFile("docs/проект/readme.md", "hello");
        writeFile("pom.txt", "root file");
        commitAll();

        List<GitFileNode> root = service.getFileTree(null);

        // Regression: without core.quotepath=false, git quotes/octal-escapes the Cyrillic path,
        // which used to split into a bogus quoted "docs" node distinct from the real one.
        List<GitFileNode> docsNodes = root.stream().filter(n -> n.name().equals("docs")).toList();
        assertThat(docsNodes).hasSize(1);
        assertThat(docsNodes.get(0).path()).isEqualTo("docs");
        assertThat(docsNodes.get(0).type()).isEqualTo("directory");

        List<GitFileNode> underDocs = service.getFileTree("docs");
        assertThat(underDocs).hasSize(1);
        assertThat(underDocs.get(0).path()).isEqualTo("docs/проект");
        assertThat(underDocs.get(0).type()).isEqualTo("directory");

        List<GitFileNode> underProject = service.getFileTree("docs/проект");
        assertThat(underProject).hasSize(1);
        assertThat(underProject.get(0).path()).isEqualTo("docs/проект/readme.md");
        assertThat(underProject.get(0).name()).isEqualTo("readme.md");
        assertThat(underProject.get(0).type()).isEqualTo("file");
    }

    @Test
    void sortsDirectoriesBeforeFilesThenAlphabeticallyIgnoringCase() {
        writeFile("banana.txt", "b");
        writeFile("Apple.txt", "a");
        writeFile("zebra/x.txt", "z");
        writeFile("Bird/x.txt", "b");
        commitAll();

        List<GitFileNode> root = service.getFileTree(null);

        assertThat(root.stream().map(GitFileNode::name).toList())
                .containsExactly("Bird", "zebra", "Apple.txt", "banana.txt");
    }

    @Test
    void subPathIsNeverInterpretedAsGitOption() {
        writeFile("tracked.txt", "tracked");
        commitAll();
        // Not added/committed — must never surface via ls-files, tracked or not.
        writeFile("untracked.txt", "untracked");

        // Without a "--" pathspec separator, "--others" would be parsed by `git ls-files` as the
        // --others *option* (list untracked files too) rather than a literal pathspec, leaking
        // untracked.txt. With the separator it's just a (non-existent) directory name.
        List<GitFileNode> result = service.getFileTree("--others");

        assertThat(result).isEmpty();
    }
}
