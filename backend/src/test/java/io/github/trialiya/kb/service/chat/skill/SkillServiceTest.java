package io.github.trialiya.kb.service.chat.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.trialiya.kb.model.skill.SkillContent;
import io.github.trialiya.kb.service.chat.script.ScriptEditPolicy;
import io.github.trialiya.kb.service.chat.script.ScriptGuideService;
import org.junit.jupiter.api.Test;

class SkillServiceTest {

    private static final String TUTORIAL = "### Script vs standard tool\nworked examples";
    private static final String EDIT_TUTORIAL = "### Example: bulk rename\nkb.edit pitfalls";

    private SkillService service(boolean editsAllowed) {
        ScriptGuideService guides = mock(ScriptGuideService.class);
        when(guides.tutorial()).thenReturn(TUTORIAL);
        when(guides.editTutorial()).thenReturn(EDIT_TUTORIAL);
        ScriptEditPolicy policy = mock(ScriptEditPolicy.class);
        when(policy.enabled(null)).thenReturn(editsAllowed);
        return new SkillService(guides, policy);
    }

    @Test
    void theCatalogueListsEverySkillWithItsTrigger() {
        String catalogue = service(true).catalogue(false, null);
        assertThat(catalogue)
                .contains("## Skills")
                .contains("`readSkill`")
                .contains("`script-writing`")
                .contains("`script-editing`")
                // Правило перечитать после сжатия — вторая половина механизма выживания навыков
                // (первая — правила в summarizer.md/compactor.md).
                .contains("call `readSkill` again");
    }

    /** Навык про пишущие скрипты не предлагается там, где скриптам писать нельзя. */
    @Test
    void theEditSkillIsHiddenWhereScriptsCannotWrite() {
        String catalogue = service(false).catalogue(false, null);
        assertThat(catalogue).contains("`script-writing`").doesNotContain("`script-editing`");
    }

    /** Слабая модель несёт оба туториала прямо в системном промпте — каталог ей не показывается. */
    @Test
    void theCatalogueIsEmptyForAWeakModel() {
        assertThat(service(true).catalogue(true, null)).isEmpty();
    }

    /** Скрипты выключены — туториалы пусты, навыков нет, и каталог, и инструмент исчезают. */
    @Test
    void noSkillsExistWhenTheGuidesAreEmpty() {
        ScriptGuideService guides = mock(ScriptGuideService.class);
        when(guides.tutorial()).thenReturn("");
        when(guides.editTutorial()).thenReturn("");
        SkillService service = new SkillService(guides, mock(ScriptEditPolicy.class));
        assertThat(service.anySkills()).isFalse();
        assertThat(service.catalogue(false, null)).isEmpty();
    }

    @Test
    void readReturnsTheSkillText() {
        SkillContent content = service(true).read("script-writing", null);
        assertThat(content.name()).isEqualTo("script-writing");
        assertThat(content.content()).isEqualTo(TUTORIAL);
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
        ScriptGuideService guides = mock(ScriptGuideService.class);
        when(guides.tutorial()).thenReturn(TUTORIAL);
        when(guides.editTutorial()).thenReturn(EDIT_TUTORIAL);
        ScriptEditPolicy policy = mock(ScriptEditPolicy.class);
        when(policy.enabled("writable")).thenReturn(true);
        when(policy.enabled("readonly")).thenReturn(false);
        SkillService service = new SkillService(guides, policy);

        assertThat(service.read("script-editing", "writable").content()).isEqualTo(EDIT_TUTORIAL);
        assertThat(service.catalogue(false, "readonly")).doesNotContain("`script-editing`");
        assertThatThrownBy(() -> service.read("script-editing", "readonly"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
