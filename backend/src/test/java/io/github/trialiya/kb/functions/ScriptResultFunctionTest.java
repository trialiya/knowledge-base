package io.github.trialiya.kb.functions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import io.github.trialiya.kb.service.chat.context.AttachmentService;
import io.github.trialiya.kb.service.chat.script.InMemoryScriptResultStore;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ToolContext;

/** {@link ScriptResultFunction#saveScriptResult}: a kept value becomes a chat attachment. */
class ScriptResultFunctionTest {

    private static final String CHAT = "chat-1";

    private final ToolContext context = new ToolContext(Map.of(ChatMemory.CONVERSATION_ID, CHAT));

    private InMemoryScriptResultStore store;
    private AttachmentService attachments;
    private ScriptResultFunction function;

    @BeforeEach
    void setUp() {
        store = new InMemoryScriptResultStore();
        attachments = mock(AttachmentService.class);
        function = new ScriptResultFunction(store, attachments);
    }

    @Test
    void aStringValueIsSavedAsItIsUnderTheNameTheModelChose() {
        store.keep(CHAT, null, "kb", "\"path,count\\nsrc/App.java,3\\n\"");

        function.saveScriptResult(context, "r1", "todo.csv");

        verify(attachments)
                .createFromText(CHAT, "todo.csv", "text/csv", "path,count\nsrc/App.java,3\n");
    }

    @Test
    void anyOtherValueIsSavedAsIndentedJsonUnderADefaultName() {
        store.keep(CHAT, null, "kb", "{\"n\":3}");

        function.saveScriptResult(context, "r1", null);

        final ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(attachments)
                .createFromText(
                        eq(CHAT),
                        eq("script-result-r1.json"),
                        eq("application/json"),
                        content.capture());
        assertThat(content.getValue()).contains("\"n\" : 3").contains("\n");
    }

    @Test
    void aStringWithoutANameIsPlainText() {
        store.keep(CHAT, null, "kb", "\"hello\"");

        function.saveScriptResult(context, " r1 ", "  ");

        verify(attachments).createFromText(CHAT, "script-result-r1.txt", "text/plain", "hello");
    }

    @Test
    void anotherChatsResultIsNotThere() {
        store.keep("chat-2", null, "kb", "1");

        assertThatThrownBy(() -> function.saveScriptResult(context, "r1", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No kept result 'r1'");
        verifyNoInteractions(attachments);
    }

    @Test
    void aMissingIdIsAskedFor() {
        assertThatThrownBy(() -> function.saveScriptResult(context, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("resultId");
        verify(attachments, never()).createFromText(any(), any(), any(), any());
    }
}
