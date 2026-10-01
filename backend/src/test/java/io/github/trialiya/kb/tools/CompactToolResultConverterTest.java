package io.github.trialiya.kb.tools;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trialiya.kb.model.git.dto.FileEntryType;
import io.github.trialiya.kb.model.git.dto.GitEditResult;
import io.github.trialiya.kb.model.git.dto.GitFileNode;
import io.github.trialiya.kb.model.script.ScriptResult;
import io.github.trialiya.kb.model.script.ScriptStats;
import io.github.trialiya.kb.model.tool.ToolResult;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The model's text versus everyone else's: a {@code UiOnly} field is left out of what the model
 * reads and nowhere else, and a trimmed view is kept whole only when it actually cut something.
 */
class CompactToolResultConverterTest {

    private final CompactToolResultConverter converter = new CompactToolResultConverter();

    @AfterEach
    void clear() {
        RecordingToolCallback.CURRENT_RESULT.remove();
        RecordingToolCallback.CURRENT_FULL_TEXT.remove();
    }

    @Test
    void aUiOnlyFieldIsLeftOutOfTheModelsTextOnly() {
        GitFileNode file = new GitFileNode("src/App.java", "App.java", FileEntryType.FILE, 120L);

        String forModel = converter.convert(new ToolResult<>("kb", List.of(file)), null);
        // REST writes with no view: the Files tree still gets the name it prints.
        String forUi = JsonMapper.builder().build().writeValueAsString(file);

        assertThat(forModel)
                .isEqualTo(
                        "{\"project\":\"kb\",\"result\":[{\"path\":\"src/App.java\",\"type\":\"file\",\"size\":120}]}");
        assertThat(forUi).contains("\"name\":\"App.java\"");
    }

    @Test
    void aFlagThatIsAlmostAlwaysTrueIsPrintedOnlyWhenFalse() {
        GitFileNode dir = new GitFileNode("build", "build", FileEntryType.DIRECTORY, null, false);

        assertThat(converter.convert(dir, null))
                .isEqualTo("{\"path\":\"build\",\"type\":\"directory\",\"tracked\":false}");
    }

    @Test
    void aViewThatCutTheDiffKeepsTheWholeResult() {
        String shown = converter.convert(new GitEditResult("edit", "a.md", 1, 1, 3, "@@ -1 +1 @@\n-a\n+b"), null);

        assertThat(shown).doesNotContain("diff");
        assertThat(RecordingToolCallback.CURRENT_FULL_TEXT.get()).contains("\"diff\":\"@@ -1 +1 @@");
    }

    @Test
    void aViewThatOnlyDroppedEmptyFieldsKeepsNoSecondCopy() {
        converter.convert(new GitEditResult("create", "a.md", 3, 0, 3, null), null);
        assertThat(RecordingToolCallback.CURRENT_FULL_TEXT.get()).isNull();

        ScriptResult run = new ScriptResult(
                "kb", null, null, 7, List.of(), new ScriptStats(1, 2, 3, 0, 4), null, List.of("a.md"), List.of());
        String shown = converter.convert(run, null);
        assertThat(shown).doesNotContain("\"log\"", "\"edits\"", "\"error\"");
        assertThat(RecordingToolCallback.CURRENT_FULL_TEXT.get()).isNull();
    }

    @Test
    void stringsKeepSpringAisHandling() {
        assertThat(converter.convert("{\"a\":1}", String.class)).isEqualTo("{\"a\":1}");
        assertThat(converter.convert("plain", String.class)).isEqualTo("\"plain\"");
    }
}
