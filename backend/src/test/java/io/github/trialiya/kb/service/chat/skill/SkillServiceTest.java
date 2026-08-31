package io.github.trialiya.kb.service.chat.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.trialiya.kb.config.model.ScriptProperties;
import io.github.trialiya.kb.model.project.Project;
import io.github.trialiya.kb.model.project.ProjectSkill;
import io.github.trialiya.kb.model.skill.SkillContent;
import io.github.trialiya.kb.service.chat.script.ScriptEditPolicy;
import io.github.trialiya.kb.service.file.project.ProjectCatalog;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkillServiceTest {

    @TempDir Path tree;

    private SkillService service(boolean editsAllowed) {
        ScriptEditPolicy policy = mock(ScriptEditPolicy.class);
        when(policy.enabled(null)).thenReturn(editsAllowed);
        return new SkillService(ScriptProperties.enabledWithDefaults(), policy, catalogWith("kb"));
    }

    /**
     * Каталог из одного проекта с этими навыками: он и дефолтный, и ответ на свой id — как {@code
     * ProjectCatalog} и отвечает.
     */
    private ProjectCatalog catalogWith(String projectId, ProjectSkill... skills) {
        Project project =
                new Project(
                        projectId,
                        projectId,
                        tree,
                        false,
                        false,
                        List.of(),
                        List.of(skills),
                        false,
                        false);
        ProjectCatalog catalog = mock(ProjectCatalog.class);
        when(catalog.projects()).thenReturn(List.of(project));
        when(catalog.find(nullable(String.class))).thenReturn(Optional.empty());
        when(catalog.find(projectId)).thenReturn(Optional.of(project));
        when(catalog.defaultProject()).thenReturn(project);
        return catalog;
    }

    private SkillService serviceWithProjectSkill(String projectId, ProjectSkill... skills) {
        ScriptEditPolicy policy = mock(ScriptEditPolicy.class);
        when(policy.enabled(nullable(String.class))).thenReturn(true);
        return new SkillService(
                ScriptProperties.enabledWithDefaults(), policy, catalogWith(projectId, skills));
    }

    private ProjectSkill written(String name, String text) throws IOException {
        Path file = tree.resolve(name + ".md");
        Files.writeString(file, text);
        return new ProjectSkill(name, "when testing " + name, file);
    }

    @Test
    void theCatalogueListsEverySkillWithItsTrigger() {
        String catalogue = service(true).catalogue(null);
        assertThat(catalogue)
                .contains("## Skills")
                .contains("`script-writing`")
                .contains("`script-editing`")
                // Правило перечитать после сжатия — вторая половина механизма выживания навыков
                // (первая — правила в summarizer.md/compactor.md).
                .contains("call `readSkill` again");
    }

    /** Навык про пишущие скрипты не предлагается там, где скриптам писать нельзя. */
    @Test
    void theEditSkillIsHiddenWhereScriptsCannotWrite() {
        String catalogue = service(false).catalogue(null);
        assertThat(catalogue).contains("`script-writing`").doesNotContain("`script-editing`");
    }

    /** Скрипты выключены, проектных навыков нет — инструмент не регистрируется, каталог пуст. */
    @Test
    void noSkillsExistWhenScriptsAreOffAndNoProjectDefinesAny() {
        SkillService service =
                new SkillService(
                        new ScriptProperties(
                                false, false, null, null, null, null, null, null, null, null),
                        mock(ScriptEditPolicy.class),
                        mock(ProjectCatalog.class));
        assertThat(service.anySkills()).isFalse();
        assertThat(service.catalogue(null)).isEmpty();
    }

    /** Текст навыка — тот самый файл из {@code prompt/skills/}, а не пересказ. */
    @Test
    void readReturnsTheSkillFile() {
        SkillContent content = service(true).read("script-writing", null);
        assertThat(content.name()).isEqualTo("script-writing");
        assertThat(content.content())
                .contains("# Skill: writing scripts")
                .contains("### Script vs standard tool")
                .contains("kb.grep");

        assertThat(service(true).read("script-editing", null).content())
                .contains("# Skill: scripts that edit files")
                .contains("kb.edit");
    }

    /**
     * Подстановок лимитов в файлах навыков нет и быть не должно: их читает {@link SkillService}
     * напрямую, мимо {@code ScriptGuideService}, — сырые {@code &#123;&#123;…&#125;&#125;} уехали
     * бы модели как есть.
     */
    @Test
    void skillFilesCarryNoBudgetPlaceholders() {
        SkillService service = service(true);
        assertThat(service.read("script-writing", null).content()).doesNotContain("{{");
        assertThat(service.read("script-editing", null).content()).doesNotContain("{{");
    }

    /** Ответ на незнакомое имя — это ответ модели: он обязан назвать доступные навыки. */
    @Test
    void anUnknownNameIsRefusedWithTheAvailableSkillsNamed() {
        assertThatThrownBy(() -> service(true).read("no-such-skill", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no-such-skill")
                .hasMessageContaining("script-writing")
                .hasMessageContaining("script-editing");
    }

    @Test
    void theEditSkillIsRefusedWhereScriptsCannotWrite() {
        assertThatThrownBy(() -> service(false).read("script-editing", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot write")
                .hasMessageContaining("script-writing");
    }

    /** Решение про правки — попроектное, и доступность навыка следует за проектом вызова. */
    @Test
    void theEditSkillFollowsTheProjectOfTheCall() {
        ScriptEditPolicy policy = mock(ScriptEditPolicy.class);
        when(policy.enabled("writable")).thenReturn(true);
        when(policy.enabled("readonly")).thenReturn(false);
        SkillService service =
                new SkillService(ScriptProperties.enabledWithDefaults(), policy, catalogWith("kb"));

        assertThat(service.read("script-editing", "writable").content()).contains("kb.edit");
        assertThat(service.catalogue("readonly")).doesNotContain("`script-editing`");
        assertThatThrownBy(() -> service.read("script-editing", "readonly"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── Проектные навыки ─────────────────────────────────────────────────────

    /**
     * Проектный навык объявляется в блоке {@code <active-project>}, а не в системном промпте:
     * попади его имя в каталог, системный промпт менялся бы со сменой проекта — и рвал бы кэш всего
     * контекста (см. javadoc {@code SkillService}). В каталоге вместо списка — константная отсылка.
     */
    @Test
    void aProjectSkillIsAnnouncedInTheProjectBlockNotTheSystemCatalogue() throws IOException {
        ProjectCatalog catalog = catalogWith("kb", written("release", "# Release"));
        ScriptEditPolicy policy = mock(ScriptEditPolicy.class);
        when(policy.enabled(nullable(String.class))).thenReturn(true);
        SkillService service =
                new SkillService(ScriptProperties.enabledWithDefaults(), policy, catalog);

        String block = service.projectSkills(catalog.defaultProject());
        assertThat(block).contains("`release`").contains("when testing release");

        assertThat(service.catalogue("kb"))
                .doesNotContain("`release`")
                .contains("<active-project>");

        // Проекту без навыков секция не достаётся вовсе — блок и так переоплачивается каждый ход.
        Project bare =
                new Project("bare", "bare", tree, false, false, List.of(), List.of(), false, false);
        assertThat(service.projectSkills(bare)).isEmpty();
    }

    @Test
    void aProjectSkillIsReadFromTheWorkingTreeAtCallTime() throws IOException {
        ProjectSkill skill = written("release", "# Release checklist\nSteps.");
        SkillService service = serviceWithProjectSkill("kb", skill);

        SkillContent content = service.read("release", "kb");
        assertThat(content.name()).isEqualTo("release");
        assertThat(content.content()).isEqualTo("# Release checklist\nSteps.");

        // Файл обновился — следующая загрузка видит новый текст без рестарта.
        Files.writeString(skill.file(), "# Release checklist v2");
        assertThat(service.read("release", "kb").content()).isEqualTo("# Release checklist v2");
    }

    /**
     * Навык чужого (в т.ч. прежнего) проекта недоступен и неотличим от несуществующего: отказ
     * перечисляет встроенные и навыки активного проекта — чтобы модель после смены проекта не
     * работала по инструкциям прежнего репозитория.
     */
    @Test
    void anotherProjectsSkillIsUnknownHere() throws IOException {
        Project kb =
                new Project(
                        "kb",
                        "kb",
                        tree,
                        false,
                        false,
                        List.of(),
                        List.of(written("release", "# Release")),
                        false,
                        false);
        Project other =
                new Project(
                        "other", "other", tree, false, false, List.of(), List.of(), false, false);
        ProjectCatalog catalog = mock(ProjectCatalog.class);
        when(catalog.projects()).thenReturn(List.of(kb, other));
        when(catalog.find("kb")).thenReturn(Optional.of(kb));
        when(catalog.find("other")).thenReturn(Optional.of(other));
        when(catalog.defaultProject()).thenReturn(kb);
        ScriptEditPolicy policy = mock(ScriptEditPolicy.class);
        when(policy.enabled(nullable(String.class))).thenReturn(true);
        SkillService service =
                new SkillService(ScriptProperties.enabledWithDefaults(), policy, catalog);

        assertThat(service.read("release", "kb").content()).isEqualTo("# Release");

        // А вызов из чужого проекта того же развёртывания навыка не видит; в списке доступного
        // его тоже нет — только встроенные (у "other" своих навыков не объявлено).
        assertThatThrownBy(() -> service.read("release", "other"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown skill 'release'")
                .hasMessageContaining("Available skills: script-writing, script-editing.");
    }

    /** Ветка без файла навыка — легальное состояние дерева: это ответ инструмента, не 500. */
    @Test
    void aMissingSkillFileIsAToolAnswer() {
        ProjectSkill skill = new ProjectSkill("release", "when releasing", tree.resolve("gone.md"));
        SkillService service = serviceWithProjectSkill("kb", skill);

        assertThatThrownBy(() -> service.read("release", "kb"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("release")
                .hasMessageContaining("branch");
    }

    /** Файл сверх лимита в контекст не вываливается — отказ называет и размер, и предел. */
    @Test
    void anOversizedSkillFileIsRefused() throws IOException {
        Path file = tree.resolve("big.md");
        Files.writeString(file, "x".repeat((int) SkillService.MAX_PROJECT_SKILL_BYTES + 1));
        SkillService service =
                serviceWithProjectSkill("kb", new ProjectSkill("big", "never", file));

        assertThatThrownBy(() -> service.read("big", "kb"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too large")
                .hasMessageContaining(String.valueOf(SkillService.MAX_PROJECT_SKILL_BYTES));
    }

    /**
     * Тёзка встроенного навыка в конфигурации проекта — ошибка старта: {@code read} отдаёт
     * встроенные в приоритете, и проектный тёзка был бы объявлен, но не загружаем никогда.
     */
    @Test
    void aProjectSkillMustNotShadowABuiltIn() throws IOException {
        ProjectSkill shadow = written("script-writing", "# Shadow");
        assertThatThrownBy(() -> serviceWithProjectSkill("kb", shadow))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("script-writing")
                .hasMessageContaining("built-in");
    }

    /**
     * Скрипты выключены, но проект навыки объявил: инструмент нужен, каталог рендерится — без
     * встроенного списка, с отсылкой к блоку проекта.
     */
    @Test
    void projectSkillsAloneKeepTheToolRegistered() throws IOException {
        SkillService service =
                new SkillService(
                        new ScriptProperties(
                                false, false, null, null, null, null, null, null, null, null),
                        mock(ScriptEditPolicy.class),
                        catalogWith("kb", written("release", "# Release")));

        assertThat(service.anySkills()).isTrue();
        assertThat(service.catalogue("kb"))
                .contains("## Skills")
                .contains("<active-project>")
                .doesNotContain("Always available")
                .doesNotContain("script-writing");
        assertThat(service.read("release", "kb").content()).isEqualTo("# Release");
    }
}
