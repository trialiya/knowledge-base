package io.github.trialiya.kb.functions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.trialiya.kb.model.script.ScriptResult;
import io.github.trialiya.kb.model.script.ScriptStats;
import io.github.trialiya.kb.service.chat.script.ResultScope;
import io.github.trialiya.kb.service.chat.script.ScriptRequest;
import io.github.trialiya.kb.service.chat.script.ScriptRunner;
import io.github.trialiya.kb.service.file.git.GitRegistry;
import io.github.trialiya.kb.tools.ProjectContext;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.memory.ChatMemory;
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
        when(runner.run(any(ScriptRequest.class), any()))
                .thenReturn(
                        new ScriptResult(
                                "billing",
                                null,
                                null,
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

        assertThat(ran().projectId()).isEqualTo("billing");
    }

    @Test
    void omittedProjectArgumentFallsBackToTheChatsOwnProject() {
        ToolContext context = new ToolContext(Map.of(ProjectContext.KEY, "kb"));

        function.runScript(context, "return 1;", null, null);

        assertThat(ran().projectId()).isEqualTo("kb");
    }

    @Test
    void blankProjectArgumentIsTreatedAsOmitted() {
        ToolContext context = new ToolContext(Map.of(ProjectContext.KEY, "kb"));

        function.runScript(context, "return 1;", null, "  ");

        assertThat(ran().projectId()).isEqualTo("kb");
    }

    @Test
    void namingAnotherProjectBuysReadingNeverWriting() {
        ToolContext context = new ToolContext(Map.of(ProjectContext.KEY, "kb"));

        function.runScript(context, "kb.edit(...)", null, "billing");

        // forceReadOnly=true: the repository the user chose for this chat is the only one a run
        // may write to, so the argument cannot be a way around that choice.
        assertThat(ran())
                .extracting(ScriptRequest::forceReadOnly, ScriptRequest::projectId)
                .containsExactly(true, "billing");
    }

    @Test
    void runningOnTheChatsOwnProjectKeepsWritesAvailable() {
        ToolContext context = new ToolContext(Map.of(ProjectContext.KEY, "kb"));

        function.runScript(context, "kb.edit(...)", null, null);

        assertThat(ran())
                .extracting(ScriptRequest::forceReadOnly, ScriptRequest::projectId)
                .containsExactly(false, "kb");
    }

    @Test
    void namingTheChatsOwnProjectExplicitlyIsNotAnOverride() {
        ToolContext context = new ToolContext(Map.of(ProjectContext.KEY, "kb"));

        function.runScript(context, "kb.edit(...)", null, "kb");

        assertThat(ran())
                .extracting(ScriptRequest::forceReadOnly, ScriptRequest::projectId)
                .containsExactly(false, "kb");
    }

    @Test
    void theSubAgentsCopyStaysReadOnlyEvenOnItsOwnProject() {
        ToolContext context = new ToolContext(Map.of(ProjectContext.KEY, "kb"));

        ScriptFunction.readOnly(runner, gitRegistry).runScript(context, "kb.edit(...)", null, null);

        assertThat(ran())
                .extracting(ScriptRequest::forceReadOnly, ScriptRequest::projectId)
                .containsExactly(true, "kb");
    }

    @Test
    void aChatThatStoredNoProjectStillWritesToTheDefaultOneItNames() {
        // Nothing in the context: the chat never chose, so it runs on the default project — which
        // the system prompt names, so the model may well name it back. That is not a switch away.
        ToolContext context = new ToolContext(Map.of());

        function.runScript(context, "kb.edit(...)", null, "kb");

        assertThat(ran())
                .extracting(ScriptRequest::forceReadOnly, ScriptRequest::projectId)
                .containsExactly(false, "kb");
    }

    @Test
    void theResultEchoesWhichProjectActuallyRan() {
        ToolContext context = new ToolContext(Map.of());

        ScriptResult result = function.runScript(context, "return 1;", null, "billing");

        assertThat(result.project()).isEqualTo("billing");
        assertThat(result.getFormattedResponse()).contains("billing");
    }

    @Test
    void theChatsCopyKeepsItsResultInTheChat() {
        ToolContext context = new ToolContext(Map.of(ChatMemory.CONVERSATION_ID, "chat-1"));

        function.runScript(context, "return 1;", null, null);

        assertThat(ran().results()).isEqualTo(ResultScope.keeping("chat-1"));
    }

    @Test
    void theSubAgentsCopyReadsTheChatsResultsButKeepsNone() {
        ToolContext context = new ToolContext(Map.of(ChatMemory.CONVERSATION_ID, "chat-1"));

        ScriptFunction.readOnly(runner, gitRegistry).runScript(context, "return 1;", null, null);

        assertThat(ran().results()).isEqualTo(ResultScope.readOnly("chat-1"));
    }

    /** The one request the function handed the runner. */
    private ScriptRequest ran() {
        ArgumentCaptor<ScriptRequest> request = ArgumentCaptor.forClass(ScriptRequest.class);
        verify(runner).run(request.capture(), any());
        return request.getValue();
    }

    /** What {@code ProjectCatalog#require} does: no project named means the default one, "kb". */
    private static String canonical(Object projectId) {
        String id = projectId == null ? null : projectId.toString();
        return id == null || id.isBlank() ? "kb" : id;
    }
}
