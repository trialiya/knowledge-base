package io.github.trialiya.kb.functions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.trialiya.kb.model.git.dto.GitCommit;
import io.github.trialiya.kb.model.git.dto.GitCommitSearchResult;
import io.github.trialiya.kb.model.git.dto.GitFileBlame;
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
 * Инструменты истории ({@link GitHistoryFunction}): что из аргументов модели доезжает до сервиса и
 * как — флаги по умолчанию, диапазоны, выбор между листанием, поиском и историей строк. Какой
 * репозиторий читается и что ответ его называет — {@code GitFunctionTest}, общее для обоих классов.
 */
class GitHistoryFunctionTest {

    private GitHistoryFunction history;
    private GitService billing;

    @BeforeEach
    void setUp() {
        GitRegistry gitRegistry = mock(GitRegistry.class);
        history = new GitHistoryFunction(gitRegistry);
        billing = mock(GitService.class);
        when(billing.project())
                .thenReturn(new Project(
                        "billing", "Billing", Path.of("/repo"), false, false, null, null, null, false, false));
        when(billing.getCommitLog(anyInt(), any(), anyBoolean()))
                .thenReturn(new GitCommitSearchResult(List.of(commit()), true));
        when(billing.searchCommitLog(anyString(), anyInt(), any(), anyBoolean()))
                .thenReturn(new GitCommitSearchResult(List.of(commit()), true));
        when(billing.getLineLog(anyString(), any(), any(), anyInt(), anyBoolean()))
                .thenReturn(new GitCommitSearchResult(List.of(commit()), false));
        when(billing.getBlame(any(), anyString(), any(), any(), anyBoolean()))
                .thenReturn(new GitFileBlame("pom.xml", null, 10, List.of(), 1, 2));
        when(gitRegistry.forProject("billing")).thenReturn(billing);
    }

    private static GitCommit commit() {
        return new GitCommit("abc1234def", "abc1234", "Test", "t@e.st", OffsetDateTime.now(), "init", null, null, null);
    }

    /**
     * Репозиторий назван один раз — обёрткой. Элементы выдачи его не повторяют: вызов читает один
     * репозиторий, и пятьсот копий одного id стоили бы контекста ровно на пустом месте.
     */
    @Test
    void theItemsInsideCarryNoProjectOfTheirOwn() {
        ToolContext context = new ToolContext(Map.of());

        ToolResult<List<GitCommit>> log = history.getCommitLog(context, null, null, null, null, null, null, "billing");

        assertThat(log.project()).isEqualTo("billing");
        assertThat(GitCommit.class.getRecordComponents()).noneMatch(component -> "project".equals(component.getName()));
    }

    /**
     * Пропущенный флаг — это «истории без тел», а не отказ вызова: {@code null} доезжает до сервиса
     * как {@code false}, и модель, которая поле не заполнила, получает обычный лог.
     */
    @Test
    void theMessageBodyIsOffUnlessTheCallAsksForIt() {
        ToolContext context = new ToolContext(Map.of(ProjectContext.KEY, "billing"));

        history.getCommitLog(context, null, null, null, null, null, null, null);
        verify(billing).getCommitLog(20, null, false);

        history.getCommitLog(context, 5, null, null, true, null, null, null);
        verify(billing).getCommitLog(5, null, true);
    }

    /**
     * Диапазон и ревизия доезжают до сервиса как есть: укладывает диапазон в файл сервис ({@code
     * GitServiceBlameTest}), предел ханков — {@code BlameBudgetTest}.
     */
    @Test
    void blameHandsTheRangeAndRevisionToTheService() {
        ToolContext context = new ToolContext(Map.of(ProjectContext.KEY, "billing"));

        history.getBlame(context, "pom.xml", 3, 9, "HEAD~1", null, null);

        verify(billing).getBlame("HEAD~1", "pom.xml", 3, 9, false);
    }

    /**
     * Пропущенный {@code exact} — «кто написал строки», а не отказ; {@code exact=true} доезжает до
     * сервиса как есть. Какое сопоставление за этим стоит, решает сервис по нормализованному пути
     * ({@code GitServiceBlameTest}).
     */
    @Test
    void exactArrivesAtTheServiceAndDefaultsToAuthorship() {
        ToolContext context = new ToolContext(Map.of(ProjectContext.KEY, "billing"));

        history.getBlame(context, "app/charge.py", 1, 2, null, null, null);
        history.getBlame(context, "app/charge.py", 1, 2, null, true, null);

        verify(billing).getBlame(null, "app/charge.py", 1, 2, false);
        verify(billing).getBlame(null, "app/charge.py", 1, 2, true);
    }

    /**
     * Диапазон строк у лога — другой вопрос: история этих строк ({@code git log -L}), а не поиск по
     * сообщениям; без файла его не задать, с {@code query} не совместить.
     */
    @Test
    void aLineRangeTurnsTheLogIntoTheLinesHistory() {
        ToolContext context = new ToolContext(Map.of(ProjectContext.KEY, "billing"));
        history.getCommitLog(context, 5, "pom.xml", null, null, 3, 9, null);

        verify(billing).getLineLog("pom.xml", 3, 9, 5, false);
        assertThatThrownBy(() -> history.getCommitLog(context, 5, null, null, null, 3, 9, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("filePath");
        assertThatThrownBy(() -> history.getCommitLog(context, 5, "pom.xml", "retry", null, 3, 9, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("query");
    }

    /**
     * С запросом история ищется по сообщениям, без него — просто листается: пустой запрос — это «не
     * задан», а не «совпадает со всем».
     */
    @Test
    void aQueryTurnsTheLogIntoASearchThroughMessages() {
        ToolContext context = new ToolContext(Map.of(ProjectContext.KEY, "billing"));

        history.getCommitLog(context, 5, "src", "  retry  ", null, null, null, null);
        verify(billing).searchCommitLog("retry", 5, "src", false);

        history.getCommitLog(context, 5, "src", " ", true, null, null, null);
        verify(billing).getCommitLog(5, "src", true);
    }

    /**
     * И поиск, и простое листание говорят, дошли ли до конца: просьба о двухстах коммитах даёт сто,
     * и без признака модель прочла бы сотый как самый старый.
     */
    @Test
    void bothSearchAndListingSayWhetherTheyWereCutShort() {
        ToolContext context = new ToolContext(Map.of(ProjectContext.KEY, "billing"));

        assertThat(history.getCommitLog(context, 5, null, "retry", null, null, null, null)
                        .truncated())
                .isTrue();
        assertThat(history.getCommitLog(context, 5, null, null, null, null, null, null)
                        .truncated())
                .isTrue();

        // И «не обрезано» доезжает как есть, а не превращается в true.
        when(billing.getCommitLog(anyInt(), any(), anyBoolean()))
                .thenReturn(new GitCommitSearchResult(List.of(commit()), false));
        when(billing.searchCommitLog(anyString(), anyInt(), any(), anyBoolean()))
                .thenReturn(new GitCommitSearchResult(List.of(commit()), false));
        assertThat(history.getCommitLog(context, 5, null, "retry", null, null, null, null)
                        .truncated())
                .isFalse();
        assertThat(history.getCommitLog(context, 5, null, null, null, null, null, null)
                        .truncated())
                .isFalse();
    }
}
