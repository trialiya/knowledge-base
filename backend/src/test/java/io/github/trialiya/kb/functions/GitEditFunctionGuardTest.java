package io.github.trialiya.kb.functions;

import static io.github.trialiya.kb.tools.ToolInvocationCollector.ToolInvocationStatus.ERROR;
import static io.github.trialiya.kb.tools.ToolInvocationCollector.ToolInvocationStatus.OK;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.trialiya.kb.model.git.dto.GitEditResult;
import io.github.trialiya.kb.model.tool.ToolInvocation;
import io.github.trialiya.kb.service.GitService;
import io.github.trialiya.kb.tools.ToolInvocationCollector;
import io.github.trialiya.kb.tools.ToolInvocationCollector.ToolInvocationStatus;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

/**
 * Read-before-edit guard of {@link GitEditFunction#editFile}: the target file must have been "seen"
 * earlier in the same chat-response session — via a read tool called with the same {@code
 * filePath}, or via any successful tool result mentioning the path (grep/search/diff), so partial
 * reads and search hits are enough.
 */
class GitEditFunctionGuardTest {

    private static final String PATH = "src/main/java/App.java";

    private GitService gitService;
    private GitEditFunction function;
    private ToolInvocationCollector collector;
    private ToolContext context;

    @BeforeEach
    void setUp() {
        gitService = mock(GitService.class);
        function = new GitEditFunction(gitService);
        collector = new ToolInvocationCollector();
        context = new ToolContext(Map.of(ToolInvocationCollector.KEY, collector));
        when(gitService.editFile(anyString(), anyString(), anyString(), anyBoolean()))
                .thenReturn(new GitEditResult("edit", PATH, 1, 1, 10, "diff"));
    }

    @Test
    void editWithoutPriorSightingIsRejected() {
        assertThatThrownBy(() -> function.editFile(context, PATH, "a", "b", false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("НЕ изменён")
                .hasMessageContaining("getFileContent");
        verify(gitService, never()).editFile(anyString(), anyString(), anyString(), anyBoolean());
    }

    @Test
    void editAfterGetFileContentOfSameFilePasses() {
        record("getFileContent", Map.of("filePath", PATH), OK, null);

        assertThatCode(() -> function.editFile(context, PATH, "a", "b", false))
                .doesNotThrowAnyException();
        verify(gitService).editFile(PATH, "a", "b", false);
    }

    @Test
    void partialReadViaOutlineCounts() {
        record("getFileOutline", Map.of("filePath", PATH), OK, null);

        assertThatCode(() -> function.editFile(context, PATH, "a", "b", false))
                .doesNotThrowAnyException();
    }

    @Test
    void grepResultMentioningThePathCounts() {
        record(
                "grepContent",
                Map.of("pattern", "foo"),
                OK,
                "[{\"path\":\"" + PATH + "\",\"line\":3,\"text\":\"foo\"}]");

        assertThatCode(() -> function.editFile(context, PATH, "a", "b", false))
                .doesNotThrowAnyException();
    }

    @Test
    void searchCodebaseResultMentioningThePathCounts() {
        record("searchCodebase", Map.of("query", "app"), OK, "Найдено в " + PATH + ":10");

        assertThatCode(() -> function.editFile(context, PATH, "a", "b", false))
                .doesNotThrowAnyException();
    }

    @Test
    void previousEditOfSameFileCounts() {
        record("editFile", Map.of("filePath", PATH), OK, null);

        assertThatCode(() -> function.editFile(context, PATH, "a", "b", false))
                .doesNotThrowAnyException();
    }

    @Test
    void readOfDifferentFileDoesNotCount() {
        record("getFileContent", Map.of("filePath", "other/File.java"), OK, null);

        assertThatThrownBy(() -> function.editFile(context, PATH, "a", "b", false))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void failedReadDoesNotCount() {
        record("getFileContent", Map.of("filePath", PATH), ERROR, null);

        assertThatThrownBy(() -> function.editFile(context, PATH, "a", "b", false))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void missingCollectorSkipsTheCheck() {
        ToolContext noCollector = new ToolContext(Map.of());

        assertThatCode(() -> function.editFile(noCollector, PATH, "a", "b", false))
                .doesNotThrowAnyException();
    }

    @Test
    void createFileNeedsNoPriorRead() {
        when(gitService.createFile(anyString(), anyString()))
                .thenReturn(new GitEditResult("create", "new.txt", 1, 0, 1, null));

        assertThatCode(() -> function.createFile("new.txt", "content")).doesNotThrowAnyException();
    }

    private void record(
            String toolName,
            Map<Object, Object> arguments,
            ToolInvocationStatus status,
            String resultText) {
        collector.record(
                new ToolInvocation(
                        toolName,
                        arguments,
                        status,
                        null,
                        null,
                        null,
                        "{}",
                        resultText,
                        collector.nextCallIndex()));
    }
}
