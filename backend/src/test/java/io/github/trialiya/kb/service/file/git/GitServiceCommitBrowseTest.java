package io.github.trialiya.kb.service.file.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.trialiya.kb.model.git.dto.FileEntryType;
import io.github.trialiya.kb.model.git.dto.GitCommit;
import io.github.trialiya.kb.model.git.dto.GitFileNode;
import io.github.trialiya.kb.model.git.dto.GitPathView;
import io.github.trialiya.kb.support.TestProjects;
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
 * Обзор дерева на ревизии — режим, в котором файловый браузер показывает снимок коммита, а не
 * рабочее дерево.
 *
 * <p>Смысл режима в том, что незакоммиченные правки в него не просачиваются ни одним способом: ни
 * содержимым файла, ни составом дерева, ни размерами. Поэтому почти каждый тест здесь сначала
 * пачкает рабочее дерево, а потом спрашивает коммит.
 */
class GitServiceCommitBrowseTest {

    @TempDir Path repoDir;

    private GitService service;

    @BeforeEach
    void setUp() {
        runGit("init", "-q", "-b", "main");
        runGit("config", "user.email", "test@example.com");
        runGit("config", "user.name", "Test");
        write("README.md", "first\n");
        write("src/App.java", "class App {}\n");
        write("src/util/Text.java", "class Text {}\n");
        commitAll("first");
        service = TestProjects.gitService(repoDir, false);
    }

    /** Состав дерева берётся из коммита: добавленного и удалённого после него там нет. */
    @Test
    void theListingIsTheCommitsOwnNotTheWorkingTrees() {
        String first = head();
        write("src/Added.java", "class Added {}\n");
        runGit("rm", "-q", "src/App.java");
        commitAll("second");

        assertThat(names(service.getFileTreeAt(first, "src"))).containsExactly("util", "App.java");
        assertThat(names(service.getFileTree("src"))).containsExactly("util", "Added.java");
    }

    /** Незакоммиченная правка не меняет ни содержимое, ни размер в снимке коммита. */
    @Test
    void anUncommittedEditReachesNeitherTheContentNorTheSize() {
        String first = head();
        write("README.md", "a much longer line than the committed one\n");

        GitPathView view = service.browsePathAt(first, "README.md", true);

        assertThat(view.type()).isEqualTo(FileEntryType.FILE);
        assertThat(view.file()).isNotNull();
        assertThat(view.file().content()).isEqualTo("first\n");
        assertThat(view.commit()).isEqualTo(first);

        GitFileNode inRoot =
                view.tree().getFirst().nodes().stream()
                        .filter(n -> "README.md".equals(n.path()))
                        .findFirst()
                        .orElseThrow();
        assertThat(inRoot.size()).isEqualTo("first\n".length());
    }

    /** Ревизию называют как угодно, а отвечает всегда один полный хеш. */
    @Test
    void anyRevisionSpellingIsAnsweredWithTheFullHash() {
        String first = head();
        write("README.md", "second\n");
        commitAll("second");
        runGit("tag", "v1");

        assertThat(service.browsePathAt("v1", "README.md", false).commit()).isEqualTo(head());
        assertThat(service.browsePathAt("HEAD~1", "README.md", false).commit()).isEqualTo(first);
        assertThat(service.browsePathAt(first.substring(0, 7), "", false).commit())
                .isEqualTo(first);
    }

    /**
     * В коммите отслеживается всё по определению, поэтому серым в этом режиме не помечается ничто —
     * даже то, что в рабочем дереве живёт только по allow-globs.
     */
    @Test
    void everythingInACommitCountsAsTracked() {
        GitPathView view = service.browsePathAt(head(), "src/util/Text.java", true);

        assertThat(view.tracked()).isTrue();
        assertThat(view.tree()).extracting("path").containsExactly("", "src", "src/util");
        assertThat(view.tree())
                .allSatisfy(level -> assertThat(level.nodes()).allMatch(GitFileNode::tracked));
    }

    /** Каталог отвечает листингом, файл — содержимым: та же развилка, что у рабочего дерева. */
    @Test
    void aDirectoryAnswersWithItsListingAndAFileWithItsContent() {
        GitPathView dir = service.browsePathAt(head(), "src", false);
        assertThat(dir.type()).isEqualTo(FileEntryType.DIRECTORY);
        assertThat(dir.file()).isNull();
        assertThat(names(dir.nodes())).containsExactly("util", "App.java");

        GitPathView file = service.browsePathAt(head(), "src/App.java", false);
        assertThat(file.type()).isEqualTo(FileEntryType.FILE);
        assertThat(file.nodes()).isNull();
        assertThat(file.file()).isNotNull();
    }

    /** Путь, которого в этом коммите нет, — это missing, а не отказ: браузер так и рисует. */
    @Test
    void aPathTheCommitDoesNotHoldIsMissingRatherThanRefused() {
        String first = head();
        write("src/Later.java", "class Later {}\n");
        commitAll("second");

        GitPathView view = service.browsePathAt(first, "src/Later.java", true);

        assertThat(view.type()).isNull();
        assertThat(view.file()).isNull();
        assertThat(view.nodes()).isNull();
        assertThat(view.commit()).isEqualTo(first);
    }

    /** Корень коммита — каталог, и предков у него нет. */
    @Test
    void theRootOfACommitListsItsTopLevel() {
        GitPathView root = service.browsePathAt(head(), null, true);

        assertThat(root.path()).isEmpty();
        assertThat(root.type()).isEqualTo(FileEntryType.DIRECTORY);
        assertThat(root.tree()).isEmpty();
        assertThat(names(root.nodes())).containsExactly("src", "README.md");
    }

    @Test
    void anUnknownRevisionIsRefused() {
        assertThatThrownBy(() -> service.getFileTreeAt("no-such-rev", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Commit not found");
    }

    /** Выход за корень отвергается и здесь: путь нормализуется до чтения дерева. */
    @Test
    void aPathOutsideTheRepositoryIsRefused() {
        assertThatThrownBy(() -> service.browsePathAt(head(), "../outside.txt", false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Символьная ссылка в снимок не попадает: её блоб — это путь, на который она указывает, и
     * открыть её файлом нельзя, а отказ по клику унёс бы вместе с содержимым и дерево.
     */
    @Test
    void aSymlinkIsNotListedAsAFileOfTheCommit() throws IOException {
        Files.createSymbolicLink(repoDir.resolve("link.md"), Path.of("README.md"));
        commitAll("link");

        assertThat(names(service.getFileTreeAt(head(), ""))).doesNotContain("link.md");
        assertThat(names(service.getFileTreeAt(head(), ""))).contains("README.md");
    }

    /** Ревизия, которая коммитом не является, отвергается и историей — так же, как обзором. */
    @Test
    void aRevisionThatIsNotACommitIsRefused() {
        String treeHash = head() + "^{tree}";

        assertThatThrownBy(() -> service.getCommitLog(1, null, false, treeHash))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.getFileTreeAt(treeHash, ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** История пути от ревизии: коммиты, сделанные после неё, в ответ не попадают. */
    @Test
    void theHistoryOfARevisionStopsAtIt() {
        String first = head();
        write("README.md", "second\n");
        commitAll("second");

        assertThat(service.getCommitLog(10, "README.md", false, first))
                .extracting(GitCommit::message)
                .containsExactly("first");
        assertThat(service.getCommitLog(10, "README.md", false))
                .extracting(GitCommit::message)
                .containsExactly("second", "first");
    }

    /** Неизвестная ревизия отвергается и в истории — так же, как в обзоре дерева. */
    @Test
    void theHistoryOfAnUnknownRevisionIsRefused() {
        assertThatThrownBy(() -> service.getCommitLog(1, null, false, "no-such-ref"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Каталог, в котором нет ни одного открываемого файла, в дереве не показывается: раскрылся бы
     * он пустым — по тому же правилу, по которому в снимок не попала сама символьная ссылка.
     */
    @Test
    void aDirectoryHoldingOnlySymlinksIsNotListedEither() throws IOException {
        Files.createDirectories(repoDir.resolve("links"));
        Files.createSymbolicLink(repoDir.resolve("links/readme.md"), Path.of("../README.md"));
        commitAll("links");

        assertThat(names(service.getFileTreeAt(head(), ""))).doesNotContain("links");
        assertThat(names(service.getFileTreeAt(head(), "links"))).isEmpty();
        assertThat(names(service.browsePathAt(head(), "", false).nodes())).doesNotContain("links");
    }

    /** Размер в листинге — тоже из коммита: правка на диске его не меняет. */
    @Test
    void theListedSizeIsTheCommittedOne() {
        String first = head();
        write("README.md", "a much longer line than the committed one\n");

        GitFileNode node =
                service.getFileTreeAt(first, "").stream()
                        .filter(n -> "README.md".equals(n.path()))
                        .findFirst()
                        .orElseThrow();

        assertThat(node.size()).isEqualTo("first\n".length());
        assertThat(node.type()).isEqualTo(FileEntryType.FILE);
    }

    /**
     * Листинг спрашивают у каталога; файл и несуществующий путь отвечают пустотой, а не отказом.
     */
    @Test
    void aPathThatIsNotADirectoryListsNothing() {
        assertThat(service.getFileTreeAt(head(), "README.md")).isEmpty();
        assertThat(service.getFileTreeAt(head(), "no/such/dir")).isEmpty();
    }

    /**
     * Содержимое в этом режиме приходит из того же снимка, что и дерево, и проходит те же правила
     * показа, что чтение с диска: двоичный файл помечается флагом и отдаётся без текста.
     */
    @Test
    void aBinaryFileOfTheCommitIsFlaggedWithoutContent() {
        byte[] bytes = {0x50, 0x4B, 0x03, 0x04, 0x00, 0x01, 0x02};
        writeBytes("assets/blob.bin", bytes);
        commitAll("binary");

        GitPathView view = service.browsePathAt(head(), "assets/blob.bin", false);

        assertThat(view.type()).isEqualTo(FileEntryType.FILE);
        assertThat(view.file()).isNotNull();
        assertThat(view.file().binary()).isTrue();
        assertThat(view.file().content()).isNull();
        assertThat(view.file().sizeBytes()).isEqualTo(bytes.length);
        assertThat(view.file().commit()).isEqualTo(head());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static List<String> names(@org.jspecify.annotations.Nullable List<GitFileNode> nodes) {
        return nodes == null ? List.of() : nodes.stream().map(GitFileNode::name).toList();
    }

    private String head() {
        return service.getCommitLog(1, null, false).getFirst().hash();
    }

    private void write(String relativePath, String content) {
        try {
            Path file = repoDir.resolve(relativePath);
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.writeString(file, content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void writeBytes(String relativePath, byte[] content) {
        try {
            Path file = repoDir.resolve(relativePath);
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.write(file, content);
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
            if (process.waitFor() != 0) {
                throw new IllegalStateException(
                        "git " + String.join(" ", args) + " failed: " + output);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
