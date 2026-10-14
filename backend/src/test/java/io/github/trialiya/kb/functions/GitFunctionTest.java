package io.github.trialiya.kb.functions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.trialiya.kb.model.git.dto.FileEntryType;
import io.github.trialiya.kb.model.git.dto.GitCommit;
import io.github.trialiya.kb.model.git.dto.GitCommitSearchResult;
import io.github.trialiya.kb.model.git.dto.GitDiffEntry;
import io.github.trialiya.kb.model.git.dto.GitFileBlame;
import io.github.trialiya.kb.model.git.dto.GitFileContent;
import io.github.trialiya.kb.model.git.dto.GitFileNode;
import io.github.trialiya.kb.model.git.dto.GitFileOutline;
import io.github.trialiya.kb.model.git.dto.GitGrepHits;
import io.github.trialiya.kb.model.git.dto.GitGrepMatch;
import io.github.trialiya.kb.model.git.dto.GitLineOrigin;
import io.github.trialiya.kb.model.project.Project;
import io.github.trialiya.kb.model.tool.ToolResult;
import io.github.trialiya.kb.service.file.git.GitRegistry;
import io.github.trialiya.kb.service.file.git.GitService;
import io.github.trialiya.kb.tools.ProjectContext;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

/**
 * Which repository a read tool reads and how its answer names it — {@link GitReads}, shared by
 * {@link GitFunction} and {@link GitHistoryFunction}. Resolving an id to a repository is {@code
 * GitRegistry}'s business ({@code GitRegistryTest}), so these tests pin down which id the tools ask
 * it for, that the answer names the repository by its canonical id rather than echoing the
 * argument, and what the working-tree tools hand on to the service. The history tools' own
 * arguments are {@code GitHistoryFunctionTest}.
 *
 * <p>The echo is checked tool by tool rather than trusted: it is what tells the model which
 * repository a path belongs to, and — through {@code ProjectScoped} — what keeps a file seen in one
 * repository from counting as seen for a write into another ({@code
 * ToolInvocationCollector#hasSeenFile}). A tool that took the argument without answering in a
 * {@link ToolResult} would quietly widen that guard.
 */
class GitFunctionTest {

    private GitRegistry gitRegistry;
    private GitFunction function;
    private GitHistoryFunction history;
    private GitService billing;

    @BeforeEach
    void setUp() {
        gitRegistry = mock(GitRegistry.class);
        function = new GitFunction(gitRegistry);
        history = new GitHistoryFunction(gitRegistry);
        billing = mock(GitService.class);
        when(billing.project())
                .thenReturn(new Project(
                        "billing", "Billing", Path.of("/repo"), false, false, null, null, null, false, false));
        when(billing.getFileContent(isNull(), anyString(), any(), any()))
                .thenReturn(new GitFileContent(
                        "pom.xml", true, null, "<project/>", false, 10, "xml", 1, false, null, null));
        when(billing.grepHits(anyString(), any(), anyBoolean(), any(), anyInt(), anyInt(), anyBoolean()))
                .thenReturn(new GitGrepHits(List.of(new GitGrepMatch("pom.xml", 1, "<project/>")), true));
        when(billing.getFileTree(isNull(), any()))
                .thenReturn(List.of(new GitFileNode("src", "src", FileEntryType.DIRECTORY, null)));
        when(billing.searchFiles(anyString(), anyInt()))
                .thenReturn(List.of(new GitFileNode("pom.xml", "pom.xml", FileEntryType.FILE, 10L)));
        when(billing.getFileOutline(isNull(), anyString()))
                .thenReturn(new GitFileOutline("Foo.java", true, "java", 10, "regex", List.of()));
        when(billing.getCommitLog(anyInt(), any(), anyBoolean()))
                .thenReturn(new GitCommitSearchResult(List.of(commit()), true));
        when(billing.searchCommitLog(anyString(), anyInt(), any(), anyBoolean()))
                .thenReturn(new GitCommitSearchResult(List.of(commit()), true));
        when(billing.getCommitDiff(anyString(), anyBoolean(), any())).thenReturn(List.of(commit()));
        when(billing.getBlame(any(), anyString(), any(), any(), anyBoolean()))
                .thenReturn(new GitFileBlame(
                        "pom.xml",
                        null,
                        10,
                        List.of(new GitFileBlame.Hunk(1, 2, null, null, null, null, null, null)),
                        1,
                        2));
        when(billing.getLineOrigin(any(), anyString(), anyInt(), anyString()))
                .thenReturn(new GitLineOrigin(
                        "pom.xml", 3, "junit", null, GitLineOrigin.Status.NOT_IN_LINE, List.of(), null));
        when(billing.getUncommittedChanges(anyBoolean(), anyBoolean(), anyList()))
                .thenReturn(List.of(new GitDiffEntry("M", "pom.xml", null, 1, 0, null, null)));
        when(gitRegistry.forProject("billing")).thenReturn(billing);
    }

    private static GitCommit commit() {
        return new GitCommit("abc1234def", "abc1234", "Test", "t@e.st", OffsetDateTime.now(), "init", null, null, null);
    }

    @Test
    void explicitProjectArgumentOverridesTheChatsOwnProject() {
        ToolContext context = new ToolContext(Map.of(ProjectContext.KEY, "kb"));

        function.getFileContent(context, "pom.xml", null, null, null, "billing");

        verify(gitRegistry).forProject("billing");
    }

    @Test
    void omittedProjectArgumentFallsBackToTheChatsOwnProject() {
        ToolContext context = new ToolContext(Map.of(ProjectContext.KEY, "billing"));

        function.getFileContent(context, "pom.xml", null, null, null, null);

        verify(gitRegistry).forProject("billing");
    }

    @Test
    void blankProjectArgumentIsTreatedAsOmitted() {
        ToolContext context = new ToolContext(Map.of(ProjectContext.KEY, "billing"));

        function.getFileContent(context, "pom.xml", null, null, null, "  ");

        verify(gitRegistry).forProject("billing");
    }

    /**
     * Ответ называет репозиторий его каноническим id, а не тем, как его назвали: модель подписывает
     * этим id ссылки на файлы, а {@code ToolInvocationCollector#hasSeenFile} сверяет по нему, что
     * файл видели именно в этом репозитории. Имя в контексте прогона — тот же случай, что и в
     * аргументе.
     */
    @Test
    void theResponseNamesTheRepositoryByItsIdNotByHowItWasAskedFor() {
        when(gitRegistry.forProject("Billing")).thenReturn(billing);

        ToolResult<GitFileContent> fromContext = function.getFileContent(
                new ToolContext(Map.of(ProjectContext.KEY, "Billing")), "pom.xml", null, null, null, null);

        assertThat(fromContext.project()).isEqualTo("billing");
    }

    /**
     * Каждый читающий инструмент, поимённо: забытая обёртка не ломает компиляцию соседей — она молча
     * уносит из ответа единственное место, где сказано, чей это репозиторий. Аргумент назван не
     * канонично, так что ответ, повторивший аргумент, здесь упадёт.
     */
    @Test
    void everyReadToolAnswersWithTheIdOfTheRepositoryItRead() {
        when(gitRegistry.forProject("Billing")).thenReturn(billing);
        ToolContext context = new ToolContext(Map.of(ProjectContext.KEY, "kb"));

        assertThat(List.of(
                        function.getFileContent(context, "pom.xml", null, null, null, "Billing"),
                        function.grepContent(context, "needle", null, null, null, null, null, "Billing"),
                        function.getFileTree(context, null, "Billing"),
                        function.searchFiles(context, "pom", null, "Billing"),
                        function.getFileOutline(context, "Foo.java", "Billing"),
                        function.getUncommittedChanges(context, null, null, null, "Billing"),
                        history.getCommitLog(context, null, null, null, null, null, null, "Billing"),
                        history.getCommitDiff(context, "abc1234", null, null, "Billing"),
                        history.getBlame(context, "pom.xml", 1, 2, null, null, "Billing"),
                        history.getLineOrigin(context, "pom.xml", 3, "junit", null, "Billing")))
                .allSatisfy(r -> assertThat(r.project()).isEqualTo("billing"));

        verify(gitRegistry, times(10)).forProject("Billing");
    }

    /**
     * Один инструмент читает два разных источника: {@code commit} уходит в сервис как есть, и
     * источник выбирает сервис. Пустая строка там значит то же, что пропущенный аргумент, —
     * рабочее дерево, а не отказ на «no such rev» (GitServiceTest).
     */
    @Test
    void theCommitArgumentSwitchesTheReadFromTheWorkingTreeToThatCommitsTree() {
        ToolContext context = new ToolContext(Map.of(ProjectContext.KEY, "billing"));
        when(billing.getFileContent(anyString(), anyString(), any(), any()))
                .thenReturn(new GitFileContent(
                        "pom.xml", true, "abc1234def", "<project/>", false, 10, "xml", 1, false, null, null));

        function.getFileContent(context, "pom.xml", 1, 5, "abc1234", null);
        verify(billing).getFileContent("abc1234", "pom.xml", 1, 5);

        // Пустая ревизия — тоже рабочее дерево; так её читает сам сервис (GitServiceTest), а
        // инструмент передаёт что получил.
        function.getFileContent(context, "pom.xml", null, null, "  ", null);
        verify(billing).getFileContent("  ", "pom.xml", null, null);
        function.getFileContent(context, "pom.xml", null, null, null, null);
        verify(billing).getFileContent(null, "pom.xml", null, null);
    }

    /**
     * Список путей модель шлёт одной строкой, и разбирает её инструмент: запятая — заявленный
     * разделитель, перенос строки принимается заодно, пустой аргумент значит «всё дерево».
     */
    @Test
    void thePathsArgumentArrivesAtTheServiceAsAList() {
        ToolContext context = new ToolContext(Map.of(ProjectContext.KEY, "billing"));

        function.getUncommittedChanges(context, null, null, " docs , *.java ", null);
        verify(billing).getUncommittedChanges(false, false, List.of("docs", "*.java"));

        // Смешанный список — самый частый вид списка у модели: разбор обязан понять его целиком,
        // а не половину.
        function.getUncommittedChanges(context, null, null, "docs,\nsrc\nbuild.gradle", null);
        verify(billing).getUncommittedChanges(false, false, List.of("docs", "src", "build.gradle"));

        function.getUncommittedChanges(context, null, null, "  ", null);
        function.getUncommittedChanges(context, null, null, null, null);
        verify(billing, times(2)).getUncommittedChanges(false, false, List.of());
    }

    /**
     * Неотслеживаемая половина — отдельный вопрос, и по умолчанию он не задан: инструмент отвечает
     * про то, что уйдёт в коммит. Флаг обязан дойти до сервиса, а не быть отфильтрован после.
     */
    @Test
    void theUntrackedHalfIsAskedForOnlyWhenTheFlagSaysSo() {
        ToolContext context = new ToolContext(Map.of(ProjectContext.KEY, "billing"));

        function.getUncommittedChanges(context, null, true, null, null);
        verify(billing).getUncommittedChanges(false, true, List.of());

        function.getUncommittedChanges(context, null, false, null, null);
        verify(billing).getUncommittedChanges(false, false, List.of());
    }
}
