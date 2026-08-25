package io.github.trialiya.kb.functions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.trialiya.kb.model.git.dto.FileEntryType;
import io.github.trialiya.kb.model.git.dto.GitCommit;
import io.github.trialiya.kb.model.git.dto.GitDiffEntry;
import io.github.trialiya.kb.model.git.dto.GitFileContent;
import io.github.trialiya.kb.model.git.dto.GitFileNode;
import io.github.trialiya.kb.model.git.dto.GitFileOutline;
import io.github.trialiya.kb.model.git.dto.GitGrepMatch;
import io.github.trialiya.kb.service.file.git.GitRegistry;
import io.github.trialiya.kb.service.file.git.GitService;
import io.github.trialiya.kb.tools.ProjectContext;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

/**
 * The optional {@code project} argument every read tool adds on top of the run's own context
 * project — resolving which repository is read is {@code GitRegistry}'s business ({@code
 * GitRegistryTest}), so these tests only pin down which id {@code GitFunction} asks it for, and
 * that the answer says which repository it came from.
 *
 * <p>The echo is checked tool by tool rather than trusted: {@code
 * ToolInvocationCollector#hasSeenFile} reads it to keep a file seen in one repository from counting
 * as seen for a write into another, so a tool that takes the argument without echoing would quietly
 * widen the write guard.
 */
class GitFunctionTest {

    private GitRegistry gitRegistry;
    private GitFunction function;

    @BeforeEach
    void setUp() {
        gitRegistry = mock(GitRegistry.class);
        function = new GitFunction(gitRegistry);
        GitService billing = mock(GitService.class);
        when(billing.getFileContent(anyString(), any(), any()))
                .thenReturn(
                        new GitFileContent(
                                "billing",
                                "pom.xml",
                                true,
                                "<project/>",
                                false,
                                10,
                                "xml",
                                1,
                                false,
                                null,
                                null));
        when(billing.grepContent(
                        anyString(), any(), anyBoolean(), anyInt(), anyInt(), anyBoolean()))
                .thenReturn(List.of(new GitGrepMatch("billing", "pom.xml", 1, "<project/>")));
        when(billing.getFileTree(any()))
                .thenReturn(
                        List.of(
                                new GitFileNode(
                                        "billing", "src", "src", FileEntryType.DIRECTORY, null)));
        when(billing.searchFiles(anyString(), anyInt()))
                .thenReturn(
                        List.of(
                                new GitFileNode(
                                        "billing", "pom.xml", "pom.xml", FileEntryType.FILE, 10L)));
        when(billing.getFileOutline(anyString()))
                .thenReturn(
                        new GitFileOutline("billing", "Foo.java", "java", 10, "regex", List.of()));
        when(billing.getCommitLog(anyInt(), any())).thenReturn(List.of(commit()));
        when(billing.getCommitDiff(anyString(), anyBoolean(), any())).thenReturn(List.of(commit()));
        when(billing.getUncommittedChanges(anyBoolean()))
                .thenReturn(
                        List.of(
                                new GitDiffEntry(
                                        "billing", "M", "pom.xml", null, 1, 0, null, null)));
        when(gitRegistry.forProject("billing")).thenReturn(billing);
    }

    private static GitCommit commit() {
        return new GitCommit(
                "billing",
                "abc1234def",
                "abc1234",
                "Test",
                "t@e.st",
                OffsetDateTime.now(),
                "init",
                null);
    }

    @Test
    void explicitProjectArgumentOverridesTheChatsOwnProject() {
        ToolContext context = new ToolContext(Map.of(ProjectContext.KEY, "kb"));

        function.getFileContent(context, "pom.xml", null, null, "billing");

        verify(gitRegistry).forProject("billing");
    }

    @Test
    void omittedProjectArgumentFallsBackToTheChatsOwnProject() {
        ToolContext context = new ToolContext(Map.of(ProjectContext.KEY, "billing"));

        function.getFileContent(context, "pom.xml", null, null, null);

        verify(gitRegistry).forProject("billing");
    }

    @Test
    void blankProjectArgumentIsTreatedAsOmitted() {
        ToolContext context = new ToolContext(Map.of(ProjectContext.KEY, "billing"));

        function.getFileContent(context, "pom.xml", null, null, "  ");

        verify(gitRegistry).forProject("billing");
    }

    @Test
    void theResponseEchoesWhichProjectActuallyAnswered() {
        ToolContext context = new ToolContext(Map.of());

        GitFileContent result = function.getFileContent(context, "pom.xml", null, null, "billing");

        assertThat(result.project()).isEqualTo("billing");
    }

    @Test
    void grepContentAlsoHonoursTheExplicitProjectArgument() {
        ToolContext context = new ToolContext(Map.of(ProjectContext.KEY, "kb"));

        List<GitGrepMatch> matches =
                function.grepContent(context, "needle", null, null, null, null, null, "billing");

        verify(gitRegistry).forProject("billing");
        assertThat(matches).allSatisfy(m -> assertThat(m.project()).isEqualTo("billing"));
    }

    @Test
    void everyReadToolHonoursTheExplicitProjectArgument() {
        ToolContext context = new ToolContext(Map.of(ProjectContext.KEY, "kb"));

        assertThat(function.getFileTree(context, null, "billing"))
                .allSatisfy(n -> assertThat(n.project()).isEqualTo("billing"));
        assertThat(function.searchFiles(context, "pom", null, "billing"))
                .allSatisfy(n -> assertThat(n.project()).isEqualTo("billing"));
        assertThat(function.getFileOutline(context, "Foo.java", "billing").project())
                .isEqualTo("billing");
        assertThat(function.getCommitLog(context, null, null, "billing"))
                .allSatisfy(c -> assertThat(c.project()).isEqualTo("billing"));
        assertThat(function.getCommitDiff(context, "abc1234", null, null, "billing"))
                .allSatisfy(c -> assertThat(c.project()).isEqualTo("billing"));
        assertThat(function.getUncommittedChanges(context, null, "billing"))
                .allSatisfy(e -> assertThat(e.project()).isEqualTo("billing"));

        verify(gitRegistry, times(6)).forProject("billing");
    }

    @Test
    void aReadToolWithoutTheArgumentStaysOnTheRunsProject() {
        ToolContext context = new ToolContext(Map.of(ProjectContext.KEY, "billing"));

        function.getFileTree(context, null, null);

        verify(gitRegistry).forProject("billing");
    }
}
