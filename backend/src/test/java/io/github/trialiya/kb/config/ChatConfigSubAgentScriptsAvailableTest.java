package io.github.trialiya.kb.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trialiya.kb.config.model.ScriptProperties;
import io.github.trialiya.kb.config.model.SubAgentConfig;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Naming {@code runScript} in {@code kb.search.subagent.allowed-tools} must not be able to create
 * it. With {@code kb.script.enabled=false} the tool exists nowhere, and the sub-agent must not get
 * a copy anyway — a global "scripts off" that still left the sub-agent running them, with no
 * handbook to run them by, is exactly the drift this guards.
 */
class ChatConfigSubAgentScriptsAvailableTest {

    private static final Set<String> ALLOWED = Set.of("runScript");

    @Test
    void allowListingRunScriptDoesNotConjureItWhenScriptsAreOff() {
        SubAgentConfig config = new SubAgentConfig(true, "model", 12000, 30, ALLOWED);

        assertThat(
                        ChatConfig.subAgentScriptsAvailable(
                                new ScriptProperties(
                                        false, false, null, null, null, null, null, null, null,
                                        null, null, null),
                                config))
                .isFalse();
        assertThat(
                        ChatConfig.subAgentScriptsAvailable(
                                ScriptProperties.enabledWithDefaults(), config))
                .isTrue();
    }
}
