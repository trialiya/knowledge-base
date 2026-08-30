package io.github.trialiya.kb.service.chat.skill;

import io.github.trialiya.kb.model.skill.SkillContent;
import io.github.trialiya.kb.service.chat.script.ScriptEditPolicy;
import io.github.trialiya.kb.service.chat.script.ScriptGuideService;
import java.util.List;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Навыки — инструкции, которые модель загружает по требованию инструментом {@code readSkill}
 * ({@code SkillFunction}), а узнаёт о них из каталога в системном промпте (плейсхолдер {@code
 * {skill_catalogue}}, см. {@code SystemPromptService}).
 *
 * <p>Зачем это, когда есть системный промпт: туториал по {@code runScript} велик, а нужен не в
 * каждом чате. Слабая модель получает его в промпт целиком — она не умеет вовремя позвать
 * инструмент, поэтому для неё каталог пуст, и всё остаётся как было ({@code ScriptGuideService}).
 * Сильная до навыков не видела туториала вовсе; теперь у неё каталог с триггерами и загрузка одним
 * вызовом.
 *
 * <p>Загруженный навык живёт в контексте как результат вызова инструмента, и сжатие истории его не
 * щадит: суммаризатор текст навыка не видит вообще (ему достаётся только гист — см. {@code
 * SkillContent#getFormattedResponse}), компактор видит, но пересказывать не должен. Оба обязаны
 * зафиксировать в сводке сам факт загрузки и что текст надо перечитать ({@code summarizer.md} /
 * {@code compactor.md}, «Must preserve»); правило перечитать продублировано и в каталоге — на
 * случай, если сводку писала модель, которая факт всё же уронила.
 *
 * <p>Своих конфигурационных ключей у навыков нет: пока все они — половины скриптового руководства,
 * их состав целиком выводится из {@code kb.script.*}. Навык про правки виден только там, где
 * скриптам можно писать ({@link ScriptEditPolicy} — решение попроектное, поэтому и каталог, и
 * {@link #read} спрашивают проект): перечислять модели способ, которого у неё нет, — ровно та
 * ошибка, от которой {@code ScriptGuideService} бережёт системный промпт.
 */
@Service
public class SkillService {

    private final List<Skill> skills;
    private final ScriptEditPolicy editPolicy;

    public SkillService(ScriptGuideService guides, ScriptEditPolicy editPolicy) {
        this.editPolicy = editPolicy;
        this.skills =
                List.of(
                                new Skill(
                                        "script-writing",
                                        "before writing a non-trivial `runScript`: script vs single"
                                                + " tools, how to structure one, worked examples for"
                                                + " repo-wide tasks",
                                        guides.tutorial(),
                                        false),
                                new Skill(
                                        "script-editing",
                                        "before a `runScript` that writes files: worked examples and"
                                                + " pitfalls for `kb.edit` / `kb.create` /"
                                                + " `kb.writeBytes`",
                                        guides.editTutorial(),
                                        true))
                        .stream()
                        .filter(skill -> !skill.content().isBlank())
                        .toList();
    }

    /** Есть ли хоть один навык — без единого {@code readSkill} и не регистрируется. */
    public boolean anySkills() {
        return !skills.isEmpty();
    }

    /**
     * Раздел «Skills» системного промпта, {@code ""} — когда показывать нечего. Никогда не null:
     * плейсхолдер обязан получить значение, иначе шаблон промпта не отрендерится.
     *
     * @param weak {@code ChatModelProperties.ModelOption#weak} модели прогона — слабой каталог не
     *     показывается: весь его сегодняшний состав она и так несёт в системном промпте, и
     *     предложение загрузить то же самое ещё раз только сбивало бы её с толку
     * @param projectId проект прогона; {@code null} — проект по умолчанию
     */
    public String catalogue(boolean weak, @Nullable String projectId) {
        if (weak) {
            return "";
        }
        List<Skill> available = availableFor(projectId);
        if (available.isEmpty()) {
            return "";
        }
        StringBuilder text =
                new StringBuilder(
                        """
                        ## Skills
                        A skill is an instruction file loaded on demand: call `readSkill` with its \
                        name and follow what it returns. Load a skill the moment its trigger below \
                        matches — before attempting the task it covers, not after a failed try:
                        """);
        for (Skill skill : available) {
            text.append("- `").append(skill.name()).append("` — ").append(skill.trigger());
            text.append("\n");
        }
        text.append(
                """

                A loaded skill lives in the context only as a tool result. If the conversation was \
                summarized and the summary says a skill was loaded, its text is gone — call \
                `readSkill` again before relying on it.""");
        return text.toString();
    }

    /**
     * Текст навыка по имени.
     *
     * @param projectId проект прогона — недоступный в нём навык не выдаётся, а объясняется, почему
     * @throws IllegalArgumentException незнакомое имя или недоступный навык; сообщение перечисляет
     *     доступные, и оно же — ответ инструмента модели
     */
    public SkillContent read(String name, @Nullable String projectId) {
        Skill skill = skills.stream().filter(s -> s.name().equals(name)).findFirst().orElse(null);
        if (skill == null) {
            throw new IllegalArgumentException(
                    "Unknown skill '" + name + "'. " + availableList(projectId));
        }
        if (skill.needsScriptEdit() && !editPolicy.enabled(projectId)) {
            throw new IllegalArgumentException(
                    "Skill '"
                            + name
                            + "' is not available: scripts cannot write files in this project. "
                            + availableList(projectId));
        }
        return new SkillContent(skill.name(), skill.content());
    }

    private String availableList(@Nullable String projectId) {
        List<Skill> available = availableFor(projectId);
        if (available.isEmpty()) {
            return "No skills are available.";
        }
        return "Available skills: "
                + available.stream().map(Skill::name).collect(Collectors.joining(", "))
                + ".";
    }

    private List<Skill> availableFor(@Nullable String projectId) {
        return skills.stream()
                .filter(skill -> !skill.needsScriptEdit() || editPolicy.enabled(projectId))
                .toList();
    }

    /**
     * @param trigger когда навык загружать — строчка каталога после имени
     * @param needsScriptEdit навык описывает пишущие скрипты и виден только там, где им можно
     *     писать
     */
    private record Skill(String name, String trigger, String content, boolean needsScriptEdit) {}
}
