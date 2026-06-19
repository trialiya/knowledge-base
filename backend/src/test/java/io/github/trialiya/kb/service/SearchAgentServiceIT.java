package io.github.trialiya.kb.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.trialiya.kb.config.model.SubAgentConfig;
import io.github.trialiya.kb.functions.GitFunction;
import io.github.trialiya.kb.model.search.SearchAgentResult;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;

/**
 * End-to-end test of the search sub-agent loop. The LLM's <em>decisions</em> are scripted (a mocked
 * {@link OpenAiChatModel}), but everything else is real: the {@link ToolCallingManager} executes
 * the scripted tool calls against a genuine {@link GitFunction}/{@link GitService} backed by a
 * temporary Git repository created on disk. This exercises the manual loop, real tool execution,
 * history threading, and the iteration-cap → tool-less summarization path.
 *
 * <p>Named {@code *IT} because it integrates several real subsystems (git CLI + tool calling)
 * rather than unit-testing one class in isolation. It needs a {@code git} binary but not Docker.
 */
class SearchAgentServiceIT {

    private static final String MAGIC = "AUTH_MAGIC_TOKEN_42";

    @TempDir Path repo;

    private OpenAiChatModel chatModel;
    private ToolCallingManager toolCallingManager;
    private ToolCallback[] readOnlyTools;

    @BeforeEach
    void setUp() throws Exception {
        // ── Real fixture repo with a tracked source file containing a unique token ──────────
        git("init", "-q");
        git("config", "user.email", "test@example.com");
        git("config", "user.name", "Test");
        Files.writeString(
                repo.resolve("AuthService.java"),
                "package demo;\n"
                        + "// "
                        + MAGIC
                        + "\n"
                        + "class AuthService { boolean check() { return true; } }\n",
                StandardCharsets.UTF_8);
        git("add", "-A");
        git("commit", "-q", "-m", "init");

        GitService gitService = new GitService(repo.toString(), new OutlineService());

        Set<String> allowed =
                Set.of(
                        "grepContent",
                        "searchFiles",
                        "getFileTree",
                        "getFileOutline",
                        "getFileContent");
        readOnlyTools =
                Stream.of(ToolCallbacks.from(new GitFunction(gitService)))
                        .filter(cb -> allowed.contains(cb.getToolDefinition().name()))
                        .toArray(ToolCallback[]::new);

        chatModel = mock(OpenAiChatModel.class);
        toolCallingManager = DefaultToolCallingManager.builder().build();
    }

    private SearchAgentService newService(int maxIterations) {
        SubAgentConfig cfg = new SubAgentConfig(true, "test-model", 4000, maxIterations, Set.of());
        Resource prompt = new ByteArrayResource("system".getBytes(StandardCharsets.UTF_8));
        return new SearchAgentService(chatModel, toolCallingManager, cfg, prompt, readOnlyTools);
    }

    @Test
    void happyPath_executesToolAgainstRealRepoAndReturnsReport() {
        // 1st model turn: ask to grep for the token. 2nd turn: produce the final report.
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(toolCall("grepContent", "{\"pattern\":\"" + MAGIC + "\"}"))
                .thenReturn(text("Итог: найдено в AuthService.java:2"));

        SearchAgentResult result =
                newService(6).run("Где определён " + MAGIC + "?", "code", null, null);

        assertThat(result.report()).isEqualTo("Итог: найдено в AuthService.java:2");
        assertThat(result.complete()).isTrue();
        assertThat(result.iterations()).isEqualTo(1);

        // The real grep result must have been threaded back into the follow-up prompt — proves the
        // tool was actually executed against the fixture repo, not stubbed.
        ArgumentCaptor<Prompt> prompts = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel, times(2)).call(prompts.capture());
        Prompt followUp = prompts.getAllValues().get(1);
        assertThat(toolResponseText(followUp)).contains("AuthService.java").contains(MAGIC);
    }

    @Test
    void iterationCap_forcesToolLessSummaryCall() {
        // Model never stops requesting tools; with the cap at 2, only two rounds may execute,
        // then the service must issue a final tool-less summarization call.
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(toolCall("grepContent", "{\"pattern\":\"" + MAGIC + "\"}"))
                .thenReturn(toolCall("grepContent", "{\"pattern\":\"check\"}"))
                .thenReturn(toolCall("grepContent", "{\"pattern\":\"class\"}"))
                .thenReturn(text("Итог: сводка по бюджету."));

        SearchAgentResult result = newService(2).run("исследуй " + MAGIC, null, null, null);

        assertThat(result.report()).isEqualTo("Итог: сводка по бюджету.");
        assertThat(result.complete()).isFalse();
        assertThat(result.iterations()).isEqualTo(2);

        ArgumentCaptor<Prompt> prompts = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel, times(4)).call(prompts.capture());

        Prompt finalCall = prompts.getAllValues().get(3);
        // The summarization call offers no tools (model cannot ask for more) and carries the
        // budget-reached instruction as its last user message.
        assertThat(((OpenAiChatOptions) finalCall.getOptions()).getToolCallbacks()).isEmpty();
        assertThat(lastUserText(finalCall)).contains("Лимит шагов поиска исчерпан");
    }

    @Test
    void run_neverThrowsWhenModelEmitsUnknownTool() {
        // A malformed/unknown tool call must not propagate as an exception into the parent loop.
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(toolCall("nonExistentTool", "{}"))
                .thenReturn(text("Итог: восстановился после ошибки."));

        SearchAgentResult result = newService(3).run("проверка устойчивости", null, null, null);

        assertThat(result.report()).isNotBlank();
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private static ChatResponse toolCall(String name, String argsJson) {
        AssistantMessage msg =
                AssistantMessage.builder()
                        .content("")
                        .toolCalls(
                                List.of(
                                        new AssistantMessage.ToolCall(
                                                "call-1", "function", name, argsJson)))
                        .build();
        return new ChatResponse(List.of(new Generation(msg)));
    }

    private static ChatResponse text(String content) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
    }

    private static String toolResponseText(Prompt prompt) {
        return prompt.getInstructions().stream()
                .filter(m -> m instanceof ToolResponseMessage)
                .map(m -> (ToolResponseMessage) m)
                .flatMap(m -> m.getResponses().stream())
                .map(ToolResponseMessage.ToolResponse::responseData)
                .reduce("", (a, b) -> a + b);
    }

    private static String lastUserText(Prompt prompt) {
        List<Message> messages = prompt.getInstructions();
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).getMessageType() == MessageType.USER) {
                return messages.get(i).getText();
            }
        }
        return "";
    }

    private void git(String... args) throws Exception {
        List<String> cmd = new java.util.ArrayList<>(List.of("git"));
        cmd.addAll(List.of(args));
        Process p =
                new ProcessBuilder(cmd).directory(repo.toFile()).redirectErrorStream(true).start();
        int code = p.waitFor();
        if (code != 0) {
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            throw new IllegalStateException("git " + String.join(" ", args) + " failed:\n" + out);
        }
    }
}
