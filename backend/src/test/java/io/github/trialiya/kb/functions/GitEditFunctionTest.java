package io.github.trialiya.kb.functions;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.trialiya.kb.model.git.dto.GitEditResult;
import io.github.trialiya.kb.service.file.git.GitRegistry;
import io.github.trialiya.kb.service.file.git.GitService;
import io.github.trialiya.kb.tools.ToolInvocationCollector;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

/**
 * What {@link GitEditFunction} asks of a call before it reaches {@code GitService}.
 *
 * <p>Notably <b>not</b> a prior read: the exact-match {@code oldString} contract is the safety
 * check (see the class javadoc there), and the refusals it produces are {@code GitWriter}'s, not
 * this class's. So an edit goes through on an untouched chat-response session — the case this test
 * pins, since a re-added read-before-edit guard would look like a harmless precaution.
 */
class GitEditFunctionTest {

    private static final String PATH = "src/main/java/App.java";

    private GitService gitService;
    private GitEditFunction function;
    private ToolContext context;

    @BeforeEach
    void setUp() {
        gitService = mock(GitService.class);
        final GitRegistry gitRegistry = mock(GitRegistry.class);
        when(gitRegistry.requireEditable(any())).thenReturn(gitService);
        function = new GitEditFunction(gitRegistry);
        // An empty collector: the response has made no tool call at all before this edit.
        context =
                new ToolContext(Map.of(ToolInvocationCollector.KEY, new ToolInvocationCollector()));
        when(gitService.editFile(anyString(), anyString(), anyString(), anyBoolean()))
                .thenReturn(new GitEditResult("edit", PATH, 1, 1, 10, "diff"));
    }

    @Test
    void editNeedsNoPriorRead() {
        assertThatCode(() -> function.editFile(context, PATH, "a", "b", false))
                .doesNotThrowAnyException();
        verify(gitService).editFile(PATH, "a", "b", false);
    }

    @Test
    void replaceAllDefaultsToSingleOccurrence() {
        function.editFile(context, PATH, "a", "b", null);

        verify(gitService).editFile(PATH, "a", "b", false);
    }

    @Test
    void createFileNeedsNoPriorRead() {
        when(gitService.createFile(anyString(), anyString()))
                .thenReturn(new GitEditResult("create", "new.txt", 1, 0, 1, null));

        assertThatCode(() -> function.createFile(context, "new.txt", "content"))
                .doesNotThrowAnyException();
    }
}
