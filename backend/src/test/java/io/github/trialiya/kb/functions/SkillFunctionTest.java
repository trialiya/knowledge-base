package io.github.trialiya.kb.functions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.trialiya.kb.model.skill.SkillContent;
import io.github.trialiya.kb.service.chat.skill.SkillService;
import io.github.trialiya.kb.tools.ProjectContext;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

class SkillFunctionTest {

    private final SkillService skillService = mock(SkillService.class);
    private final SkillFunction function = new SkillFunction(skillService);

    /** Навык читается в проекте прогона: имя — от модели, проект — из контекста вызова. */
    @Test
    void readsTheSkillInTheProjectOfTheRun() {
        SkillContent content = new SkillContent("script-writing", "worked examples");
        when(skillService.read("script-writing", "billing")).thenReturn(content);
        ToolContext context = new ToolContext(Map.of(ProjectContext.KEY, "billing"));

        assertThat(function.readSkill(context, "script-writing")).isSameAs(content);
        verify(skillService).read("script-writing", "billing");
    }

    @Test
    void aBlankNameIsRefusedBeforeTouchingTheCatalogue() {
        ToolContext context = new ToolContext(Map.of());
        assertThatThrownBy(() -> function.readSkill(context, " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }
}
