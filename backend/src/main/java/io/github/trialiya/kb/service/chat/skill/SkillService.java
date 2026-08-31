package io.github.trialiya.kb.service.chat.skill;

import io.github.trialiya.kb.config.model.ScriptProperties;
import io.github.trialiya.kb.model.project.Project;
import io.github.trialiya.kb.model.project.ProjectSkill;
import io.github.trialiya.kb.model.skill.SkillContent;
import io.github.trialiya.kb.service.chat.script.ScriptEditPolicy;
import io.github.trialiya.kb.service.file.project.ProjectCatalog;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

/**
 * Навыки — инструкции, которые модель загружает по требованию инструментом {@code readSkill}
 * ({@code SkillFunction}). Их два сорта, и объявлены они в разных местах промпта намеренно:
 *
 * <ul>
 *   <li><b>Встроенные</b> — файлы из {@code prompt/skills/}, перечислены в {@link #CATALOGUE} и
 *       объявляются каталогом в системном промпте (плейсхолдер {@code {skill_catalogue}}, см.
 *       {@code SystemPromptService}). Они одни на всё приложение, поэтому им место в стабильной,
 *       кэшируемой части промпта.
 *   <li><b>Проектные</b> — файлы из рабочего дерева проекта, объявленные в {@code
 *       kb.projects[].skills} ({@link ProjectSkill}). Их список — свойство разговора, а не
 *       приложения: он меняется со сменой проекта, и в системном промпте он рвал бы префиксный кэш
 *       провайдера с нулевого байта при каждой смене. Поэтому объявляются они в блоке {@code
 *       <active-project>} ({@code ProjectPromptService} зовёт {@link #projectSkills}) — тот
 *       собирается на чтении окна, в БД не попадает и до compact-раунда доезжает тем же байтом.
 *       Загружаются они только пока их проект активен; текст читается с диска в момент вызова, так
 *       что pull или смена ветки обновляют навык без рестарта.
 * </ul>
 *
 * <p>Зачем это, когда есть системный промпт: обучающий текст велик, а нужен не в каждом чате. Один
 * навык — один файл; встроенный заводится строкой в {@link #CATALOGUE}, проектный — записью в
 * конфигурации. Слабой модели загрузить встроенный навык велит само руководство по скриптам ({@code
 * script-run-extended.md} — сегодня это указание и есть его расширенная половина), потому что ждать
 * от неё выбора «по триггеру» не приходится.
 *
 * <p>Загруженный навык живёт в контексте как результат вызова инструмента, и сжатие истории его не
 * щадит: суммаризатор текст навыка не видит вообще (ему достаётся только гист — см. {@code
 * SkillContent#getFormattedResponse}), компактор видит, но пересказывать не должен. Оба обязаны
 * зафиксировать в сводке сам факт загрузки и что текст надо перечитать ({@code summarizer.md} /
 * {@code compactor.md}, «Must preserve»); правило перечитать продублировано и в каталоге — на
 * случай, если сводку писала модель, которая факт всё же уронила.
 *
 * <p>Своих конфигурационных ключей у встроенных навыков нет: пока все они — про {@code runScript},
 * их доступность целиком выводится из {@code kb.script.*}. Навык про правки виден только там, где
 * скриптам можно писать ({@link ScriptEditPolicy} — решение попроектное, поэтому и каталог, и
 * {@link #read} спрашивают проект): перечислять модели способ, которого у неё нет, — ровно та
 * ошибка, от которой {@code ScriptGuideService} бережёт системный промпт.
 */
@Service
public class SkillService {

    /**
     * Все встроенные навыки, какие бывают. Тексты лежат отдельными файлами, здесь — только имя,
     * триггер для каталога и условие доступности; текст в конструкторе читается один раз.
     */
    private static final List<SkillDefinition> CATALOGUE =
            List.of(
                    new SkillDefinition(
                            "script-writing",
                            "before writing a non-trivial `runScript`: script vs single tools, how"
                                    + " to structure one, worked examples for repo-wide tasks",
                            "prompt/skills/script-writing.md",
                            false),
                    new SkillDefinition(
                            "script-editing",
                            "before a `runScript` that writes files: worked examples and pitfalls"
                                    + " for `kb.edit` / `kb.create` / `kb.writeBytes`",
                            "prompt/skills/script-editing.md",
                            true));

    /**
     * Потолок текста проектного навыка, в байтах файла. Встроенные навыки — порядка десяти
     * килобайт; навык, который не помещается в этот запас с четырёхкратным верхом, — уже не
     * инструкция, а свалка в контекст, и честнее отказать, чем молча его туда вывалить.
     */
    static final long MAX_PROJECT_SKILL_BYTES = 64 * 1024;

    private final List<Skill> skills;
    private final ScriptEditPolicy editPolicy;
    private final ProjectCatalog projects;

    /** Есть ли проектные навыки хоть у одного проекта — константа старта, как и весь каталог. */
    private final boolean anyProjectSkills;

    public SkillService(
            ScriptProperties scriptProperties,
            ScriptEditPolicy editPolicy,
            ProjectCatalog projects) {
        this.editPolicy = editPolicy;
        this.projects = projects;
        // Сегодня каждый встроенный навык — про runScript, поэтому без самого инструмента их нет:
        // руководство по способу, которого у модели нет, — потраченный контекст и потраченные
        // попытки.
        this.skills =
                scriptProperties.enabled() ? CATALOGUE.stream().map(Skill::of).toList() : List.of();
        this.anyProjectSkills =
                projects.projects().stream().anyMatch(project -> !project.skills().isEmpty());
        requireNoBuiltInCollisions(projects.projects());
    }

    /**
     * Имя проектного навыка, совпавшее со встроенным, — ошибка конфигурации, и падает она на
     * старте: у {@link #read} встроенные в приоритете, так что проектный тёзка был бы объявлен в
     * блоке проекта, но не загружаем никогда. Проверяется против {@link #CATALOGUE} целиком, а не
     * против включённых: с выключенными скриптами коллизия не менее ошибочна — она всплывёт первым
     * же их включением.
     */
    private static void requireNoBuiltInCollisions(List<Project> configured) {
        for (Project project : configured) {
            for (ProjectSkill skill : project.skills()) {
                if (CATALOGUE.stream().anyMatch(d -> d.name().equals(skill.name()))) {
                    throw new IllegalStateException(
                            "kb.projects["
                                    + project.id()
                                    + "].skills: \""
                                    + skill.name()
                                    + "\" is a built-in skill's name — rename the project skill");
                }
            }
        }
    }

    /**
     * Есть ли хоть один навык, встроенный или проектный, — без единого {@code readSkill} и не
     * регистрируется.
     */
    public boolean anySkills() {
        return !skills.isEmpty() || anyProjectSkills;
    }

    /**
     * Раздел «Skills» системного промпта: что такое навык, встроенный список и — константой
     * развёртывания — отсылка к проектным в блоке {@code <active-project>}. {@code ""} — когда
     * навыков нет вовсе. Никогда не null: плейсхолдер обязан получить значение, иначе шаблон
     * промпта не отрендерится.
     *
     * <p>Сами проектные навыки здесь не перечисляются никогда — их список менялся бы со сменой
     * проекта, а вместе с ним и системный промпт, то есть кэш всего контекста (см. javadoc класса).
     *
     * @param projectId проект прогона; {@code null} — проект по умолчанию
     */
    public String catalogue(@Nullable String projectId) {
        if (!anySkills()) {
            return "";
        }
        StringBuilder text =
                new StringBuilder(
                        """
                        ## Skills
                        A skill is an instruction file loaded on demand: call `readSkill` with its \
                        name and follow what it returns. Load a skill the moment its trigger \
                        matches — before attempting the task it covers, not after a failed try.""");
        List<Skill> available = availableFor(projectId);
        if (!available.isEmpty()) {
            text.append("\nAlways available:");
            for (Skill skill : available) {
                text.append("\n- `").append(skill.name()).append("` — ").append(skill.trigger());
            }
        }
        if (anyProjectSkills) {
            text.append(
                    """
                    \n
                    The active repository may define skills of its own: they are listed in the \
                    `<active-project>` block next to the current question, and they load only \
                    while that repository stays the active project — after a project switch they \
                    are no longer available.""");
        }
        text.append(
                """
                \n
                A loaded skill lives in the context only as a tool result. If the conversation was \
                summarized and the summary says a skill was loaded, its text is gone — call \
                `readSkill` again before relying on it.""");
        return text.toString();
    }

    /**
     * Список навыков одного проекта для блока {@code <active-project>}; {@code ""} — когда проекту
     * нечего объявить. Коротко намеренно: блок пересобирается на каждой итерации tool-цикла и
     * переоплачивается каждый ход — что такое навык и правило перечитывания уже сказаны разделом
     * «Skills» системного промпта, который рендерится всегда, когда этой секции есть на что
     * ссылаться (см. {@link #catalogue}).
     */
    public String projectSkills(Project project) {
        if (project.skills().isEmpty()) {
            return "";
        }
        StringBuilder text =
                new StringBuilder(
                        "\n\nSkills this repository defines — load with `readSkill` the moment the"
                                + " trigger matches (see \"Skills\" in the system prompt):");
        for (ProjectSkill skill : project.skills()) {
            text.append("\n- `").append(skill.name()).append("` — ").append(skill.trigger());
        }
        return text.toString();
    }

    /**
     * Текст навыка по имени: встроенный или объявленный <b>активным</b> проектом. Навык чужого
     * проекта не выдаётся и не отличим от несуществующего — доступное перечисляется в отказе, и
     * этого достаточно, чтобы модель после смены проекта не работала по инструкциям прежнего
     * репозитория.
     *
     * @param projectId проект прогона — недоступный в нём навык не выдаётся, а объясняется, почему
     * @throws IllegalArgumentException незнакомое имя или недоступный навык; сообщение перечисляет
     *     доступные, и оно же — ответ инструмента модели
     */
    public SkillContent read(String name, @Nullable String projectId) {
        Skill builtIn = skills.stream().filter(s -> s.name().equals(name)).findFirst().orElse(null);
        if (builtIn != null) {
            if (builtIn.needsScriptEdit() && !editPolicy.enabled(projectId)) {
                throw new IllegalArgumentException(
                        "Skill '"
                                + name
                                + "' is not available: scripts cannot write files in this project. "
                                + availableList(projectId));
            }
            return new SkillContent(builtIn.name(), builtIn.content());
        }
        ProjectSkill projectSkill =
                activeProject(projectId).skills().stream()
                        .filter(skill -> skill.name().equals(name))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "Unknown skill '"
                                                        + name
                                                        + "'. "
                                                        + availableList(projectId)));
        return new SkillContent(name, readProjectFile(projectSkill));
    }

    /**
     * Текст навыка для того, кто загрузить его не может, — сейчас это поисковый суб-агент: его
     * набор инструментов задан явным allow-list'ом без {@code readSkill}, каталога в его промпте
     * нет, а бюджет итераций жёсткий, и тратить одну на загрузку текста, который можно отдать
     * сразу, дороже, чем этот текст занести. Только встроенные: у суб-агента нет разговора, чей
     * активный проект дал бы право на проектные. Ворот доступности здесь нет намеренно: зовущий уже
     * решил, что навык нужен, а суб-агент по построению только читает.
     *
     * @throws IllegalArgumentException навыка с таким именем не бывает — это опечатка в коде,
     *     которую надо увидеть при старте, а не молча отдать пустую строку
     */
    public String textOf(String name) {
        return skills.stream()
                .filter(skill -> skill.name().equals(name))
                .findFirst()
                .map(Skill::content)
                .orElseThrow(() -> new IllegalArgumentException("Неизвестный навык: " + name));
    }

    /**
     * Чтение с диска в момент вызова — намеренно: файл живёт в рабочем дереве, и pull или смена
     * ветки обновляют навык без рестарта. Обратная сторона той же монеты — файла может не быть
     * (ветка без него — легальное состояние дерева, см. {@code ProjectCatalog}), и это ответ
     * инструмента модели, а не ошибка сервера.
     */
    private static String readProjectFile(ProjectSkill skill) {
        Path file = skill.file();
        try {
            long size = Files.size(file);
            if (size > MAX_PROJECT_SKILL_BYTES) {
                throw new IllegalArgumentException(
                        "Skill '"
                                + skill.name()
                                + "' is too large to load ("
                                + size
                                + " bytes, the limit is "
                                + MAX_PROJECT_SKILL_BYTES
                                + ") — tell the user its file needs splitting");
            }
            return Files.readString(file, StandardCharsets.UTF_8).strip();
        } catch (NoSuchFileException e) {
            throw new IllegalArgumentException(
                    "Skill '"
                            + skill.name()
                            + "' has no file in the working tree right now — the current branch"
                            + " does not carry it");
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось прочитать навык: " + file, e);
        }
    }

    /**
     * Проект, чьи навыки видит этот вызов. Неизвестный id разрешается в дефолтный проект — тем же
     * правилом, каким его разрешает весь остальной промпт ({@code ProjectPromptService}): навыки
     * обязаны совпасть с блоком {@code <active-project>}, который модель читает.
     */
    private Project activeProject(@Nullable String projectId) {
        return projects.find(projectId).orElseGet(projects::defaultProject);
    }

    private String availableList(@Nullable String projectId) {
        List<String> available =
                Stream.concat(
                                availableFor(projectId).stream().map(Skill::name),
                                activeProject(projectId).skills().stream().map(ProjectSkill::name))
                        .toList();
        if (available.isEmpty()) {
            return "No skills are available.";
        }
        return "Available skills: " + String.join(", ", available) + ".";
    }

    private List<Skill> availableFor(@Nullable String projectId) {
        return skills.stream()
                .filter(skill -> !skill.needsScriptEdit() || editPolicy.enabled(projectId))
                .toList();
    }

    /**
     * Встроенный навык до чтения текста.
     *
     * @param trigger когда навык загружать — строчка каталога после имени
     * @param resource путь к markdown в ресурсах
     * @param needsScriptEdit навык описывает пишущие скрипты и виден только там, где им можно
     *     писать
     */
    private record SkillDefinition(
            String name, String trigger, String resource, boolean needsScriptEdit) {}

    /** Встроенный навык с прочитанным текстом. */
    private record Skill(String name, String trigger, String content, boolean needsScriptEdit) {

        static Skill of(SkillDefinition definition) {
            return new Skill(
                    definition.name(),
                    definition.trigger(),
                    read(new ClassPathResource(definition.resource())),
                    definition.needsScriptEdit());
        }

        private static String read(Resource resource) {
            try {
                return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8)
                        .strip();
            } catch (IOException e) {
                throw new UncheckedIOException("Не удалось прочитать навык: " + resource, e);
            }
        }
    }
}
