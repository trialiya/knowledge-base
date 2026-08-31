package io.github.trialiya.kb.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.trialiya.kb.config.model.ScriptProperties;
import io.github.trialiya.kb.config.model.SubAgentConfig;
import io.github.trialiya.kb.service.chat.script.ScriptEditPolicy;
import io.github.trialiya.kb.service.chat.script.ScriptGuideService;
import io.github.trialiya.kb.service.chat.skill.SkillService;
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
                                        null),
                                config))
                .isFalse();
        assertThat(
                        ChatConfig.subAgentScriptsAvailable(
                                ScriptProperties.enabledWithDefaults(), config))
                .isTrue();
    }

    /**
     * Инструкции суб-агента и его набор инструментов обязаны сходиться. Расширенная половина
     * руководства — это приказ позвать {@code readSkill}, которого у суб-агента нет: послушавшись,
     * он уронил бы поиск на несуществующем инструменте. Текст навыка слабой модели при этом
     * достаётся — тем же содержимым, к которому приказ и вёл.
     */
    @Test
    void theSubAgentIsNeverOrderedToCallASkillToolItDoesNotHave() {
        ScriptEditPolicy policy = mock(ScriptEditPolicy.class);
        when(policy.enabled(nullable(String.class))).thenReturn(true);
        ScriptProperties properties = ScriptProperties.enabledWithDefaults();
        ScriptGuideService guides = new ScriptGuideService(properties, policy);
        SkillService skills = new SkillService(properties, policy);

        String weak = ChatConfig.subAgentScriptInstructions(guides, skills, true);
        String strong = ChatConfig.subAgentScriptInstructions(guides, skills, false);

        assertThat(weak).doesNotContain("readSkill").contains("### Script vs standard tool");
        assertThat(strong).doesNotContain("readSkill", "### Script vs standard tool");
        // Справочник по kb — у обеих: без него скрипты не написать ни сильной, ни слабой.
        assertThat(weak).contains("### kb reference");
        assertThat(strong).contains("### kb reference");
        // Суб-агент только читает, что бы ни было разрешено основному чату.
        assertThat(weak).doesNotContain("kb.edit");
    }
}
