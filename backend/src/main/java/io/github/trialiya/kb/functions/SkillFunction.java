package io.github.trialiya.kb.functions;

import static io.github.trialiya.kb.tools.ToolArgs.requireText;

import io.github.trialiya.kb.model.skill.SkillContent;
import io.github.trialiya.kb.service.chat.skill.SkillService;
import io.github.trialiya.kb.tools.CompactToolResultConverter;
import io.github.trialiya.kb.tools.ProjectContext;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * Инструмент {@code readSkill}: выдаёт модели навык — встроенный из каталога в системном промпте
 * или объявленный активным проектом в блоке {@code <active-project>}. Сами навыки и их доступность
 * — {@link SkillService}; регистрируется только когда навыки есть (см. {@code
 * ChatConfig#skillFunction}).
 */
@Slf4j
@AllArgsConstructor
public class SkillFunction {

    private final SkillService skillService;

    @Tool(
            name = "readSkill",
            description =
                    """
            Load a skill — an instruction file — by name. The available skills and when to \
            load each are listed in the "Skills" section of the system prompt and, for the \
            active repository's own skills, in the <active-project> block. Call it again \
            when a loaded skill's text is no longer visible in the context.
            """,
            resultConverter = CompactToolResultConverter.class)
    public SkillContent readSkill(
            ToolContext context,
            @ToolParam(description = "Skill name from the catalogue.") String name) {
        final String skill = requireText(name, "name");
        log.info("Reading skill '{}'", skill);
        return skillService.read(skill, ProjectContext.from(context));
    }
}
