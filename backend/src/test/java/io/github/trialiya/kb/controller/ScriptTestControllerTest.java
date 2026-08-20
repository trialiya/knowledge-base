package io.github.trialiya.kb.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.trialiya.kb.config.model.ScriptProperties;
import io.github.trialiya.kb.controller.ScriptTestController.ScriptRunRequest;
import io.github.trialiya.kb.model.script.ScriptResult;
import io.github.trialiya.kb.model.script.ScriptStats;
import io.github.trialiya.kb.service.chat.script.ScriptRunner;
import io.github.trialiya.kb.tools.RunCancellation;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * The script bench is the one place where code arrives from the browser instead of from the model,
 * so the two properties that keep it from widening anything — the {@code kb.script.enabled} gate
 * and the forced read-only run — are what is worth pinning down.
 */
class ScriptTestControllerTest {

    private static final ScriptResult EMPTY_RESULT =
            new ScriptResult(
                    "default",
                    null,
                    List.of(),
                    new ScriptStats(0, 0, 0, 0, 0),
                    null,
                    List.of(),
                    List.of());

    private static ScriptProperties properties(boolean enabled) {
        return new ScriptProperties(enabled, true, null, null, null, null, null, null, null, null);
    }

    @Test
    @DisplayName("with kb.script.enabled=false the bench refuses to run anything")
    void refusesWhenDisabled() {
        ScriptRunner runner = mock(ScriptRunner.class);
        ScriptTestController controller = new ScriptTestController(runner, properties(false));

        assertThatThrownBy(() -> controller.run(new ScriptRunRequest("return 1;", null)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        verifyNoInteractions(runner);
    }

    @Test
    @DisplayName("an empty script is a bad request, not an empty run")
    void refusesEmptyScript() {
        ScriptRunner runner = mock(ScriptRunner.class);
        ScriptTestController controller = new ScriptTestController(runner, properties(true));

        assertThatThrownBy(() -> controller.run(new ScriptRunRequest("  \n ", null)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(runner);
    }

    @Test
    @DisplayName("the run is always forced read-only, whatever kb.script.edit-enabled says")
    void alwaysRunsReadOnly() {
        ScriptRunner runner = mock(ScriptRunner.class);
        when(runner.run(any(), any(), any(), eq(true))).thenReturn(EMPTY_RESULT);
        ScriptTestController controller = new ScriptTestController(runner, properties(true));

        assertThat(controller.run(new ScriptRunRequest("return 1;", 5))).isSameAs(EMPTY_RESULT);
        verify(runner).run(eq("return 1;"), eq(5), any(RunCancellation.class), eq(true));
    }
}
