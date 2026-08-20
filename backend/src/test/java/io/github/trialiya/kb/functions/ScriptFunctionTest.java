package io.github.trialiya.kb.functions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.trialiya.kb.model.script.ScriptResult;
import io.github.trialiya.kb.model.script.ScriptStats;
import io.github.trialiya.kb.service.chat.script.ScriptRunner;
import io.github.trialiya.kb.service.file.GitRegistry;
import io.github.trialiya.kb.tools.ProjectContext;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

/**
 * The optional {@code project} argument of {@link ScriptFunction#runScript}: which project id
 * reaches {@link ScriptRunner}, whose own job — resolving it to a repository and running the script
 * there — is covered by the runner's tests.
 */
class ScriptFunctionTest {

    private ScriptRunner runner;
    private GitRegistry gitRegistry;
    private ScriptFunction function;

    @BeforeEach
    void setUp() {
        runner = mock(ScriptRunner.class);
        gitRegistry = mock(GitRegistry.class);
        // "kb" is this deployment's default project, so a chat that stored none runs on it too —
        // the case that made a raw id comparison wrong.
        when(gitRegistry.sameProject(any(), any()))
                .thenAnswer(
                        call ->
                                canonical(call.getArgument(0))
                                        .equals(canonical(call.getArgument(1))));
        function = ScriptFunction.forChat(runner, gitRegistry);
        when(runner.run(anyString(), anyInt(), any(), anyBoolean(), any(), any()))
                .thenReturn(
                        new ScriptResult(
                                "billing",
                                null,
                                List.of(),
                                new ScriptStats(0, 0, 0, 0, 0),
                                null,
                                List.of(),
                                List.of()));
    }

    @Test
    void explicitProjectArgumentOverridesTheChatsOwnProject() {
        ToolContext context = new ToolContext(Map.of(ProjectContext.KEY, "kb"));

        function.runScript(context, "return 1;", null, "billing");

        verify(runner).run(anyString(), anyInt(), any(), anyBoolean(), any(), eq("billing"));
    }

    @Test
    void omittedProjectArgumentFallsBackToTheChatsOwnProject() {
        ToolContext context = new ToolContext(Map.of(ProjectContext.KEY, "kb"));

        function.runScript(context, "return 1;", null, null);

        verify(runner).run(anyString(), anyInt(), any(), anyBoolean(), any(), eq("kb"));
    }

    @Test
    void blankProjectArgumentIsTreatedAsOmitted() {
        ToolContext context = new ToolContext(Map.of(ProjectContext.KEY, "kb"));

        function.runScript(context, "return 1;", null, "  ");

        verify(runner).run(anyString(), anyInt(), any(), anyBoolean(), any(), eq("kb"));
    }

    @Test
    void namingAnotherProjectBuysReadingNeverWriting() {
        ToolContext context = new ToolContext(Map.of(ProjectContext.KEY, "kb"));

        function.runScript(context, "kb.edit(...)", null, "billing");

        // forceReadOnly=true: the repository the user chose for this chat is the only one a run
        // may write to, so the argument cannot be a way around that choice.
        verify(runner).run(anyString(), anyInt(), any(), eq(true), any(), eq("billing"));
    }

    @Test
    void runningOnTheChatsOwnProjectKeepsWritesAvailable() {
        ToolContext context = new ToolContext(Map.of(ProjectContext.KEY, "kb"));

        function.runScript(context, "kb.edit(...)", null, null);

        verify(runner).run(anyString(), anyInt(), any(), eq(false), any(), eq("kb"));
    }

    @Test
    void namingTheChatsOwnProjectExplicitlyIsNotAnOverride() {
        ToolContext context = new ToolContext(Map.of(ProjectContext.KEY, "kb"));

        function.runScript(context, "kb.edit(...)", null, "kb");

        verify(runner).run(anyString(), anyInt(), any(), eq(false), any(), eq("kb"));
    }

    @Test
    void theSubAgentsCopyStaysReadOnlyEvenOnItsOwnProject() {
        ToolContext context = new ToolContext(Map.of(ProjectContext.KEY, "kb"));

        ScriptFunction.readOnly(runner, gitRegistry).runScript(context, "kb.edit(...)", null, null);

        verify(runner).run(anyString(), anyInt(), any(), eq(true), any(), eq("kb"));
    }

    @Test
    void aChatThatStoredNoProjectStillWritesToTheDefaultOneItNames() {
        // Nothing in the context: the chat never chose, so it runs on the default project — which
        // the system prompt names, so the model may well name it back. That is not a switch away.
        ToolContext context = new ToolContext(Map.of());

        function.runScript(context, "kb.edit(...)", null, "kb");

        verify(runner).run(anyString(), anyInt(), any(), eq(false), any(), eq("kb"));
    }

    @Test
    void theResultEchoesWhichProjectActuallyRan() {
        ToolContext context = new ToolContext(Map.of());

        ScriptResult result = function.runScript(context, "return 1;", null, "billing");

        assertThat(result.project()).isEqualTo("billing");
        assertThat(result.getFormattedResponse()).contains("billing");
    }

    /** What {@code ProjectCatalog#require} does: no project named means the default one, "kb". */
    private static String canonical(Object projectId) {
        String id = projectId == null ? null : projectId.toString();
        return id == null || id.isBlank() ? "kb" : id;
    }
}
