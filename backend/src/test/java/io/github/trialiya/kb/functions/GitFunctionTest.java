package io.github.trialiya.kb.functions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.trialiya.kb.model.git.dto.GitFileContent;
import io.github.trialiya.kb.model.git.dto.GitGrepMatch;
import io.github.trialiya.kb.service.file.GitRegistry;
import io.github.trialiya.kb.service.file.GitService;
import io.github.trialiya.kb.tools.ProjectContext;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

/**
 * The optional {@code project} argument {@link GitFunction#getFileContent} and {@link
 * GitFunction#grepContent} add on top of the run's own context project — resolving which repository
 * is read is {@code GitRegistry}'s business ({@code GitRegistryTest}), so these tests only pin down
 * which id {@code GitFunction} asks it for.
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
        when(gitRegistry.forProject("billing")).thenReturn(billing);
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
}
