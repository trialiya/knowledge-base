package io.github.trialiya.kb.service.chat.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trialiya.kb.config.model.GitProperties;
import io.github.trialiya.kb.config.model.ProjectProperties;
import io.github.trialiya.kb.config.model.ProjectProperties.ProjectOption;
import io.github.trialiya.kb.model.chat.entity.ProjectSpan;
import io.github.trialiya.kb.service.file.git.GitRegistry;
import io.github.trialiya.kb.service.file.project.ProjectCatalog;
import io.github.trialiya.kb.support.TestProjects;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Что промпт говорит модели про репозитории: активный — и те, которые она вправе назвать в
 * необязательном аргументе {@code project} читающего инструмента.
 *
 * <p>Второй список — не украшение: id проекта модели взять больше неоткуда, а выдуманный падает на
 * {@code ProjectCatalog#require}. Без строки в промпте кросс-проектное чтение остаётся
 * возможностью, которой никто не пользуется.
 */
class ProjectPromptServiceTest {

    @TempDir Path root;

    private ProjectPromptService service(String... ids) throws IOException {
        List<ProjectOption> options = List.of(ids).stream().map(this::project).toList();
        ProjectCatalog catalog =
                new ProjectCatalog(new ProjectProperties(options), new GitProperties(null));
        GitRegistry registry = TestProjects.registry(options);
        return new ProjectPromptService(catalog, registry);
    }

    private static ProjectSpan span(String id, long from, long to) {
        return new ProjectSpan(id, from, to);
    }

    private ProjectOption project(String id) {
        Path dir = root.resolve(id);
        try {
            java.nio.file.Files.createDirectories(dir);
            new ProcessBuilder("git", "init", "-q").directory(dir.toFile()).start().waitFor();
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException(e);
        }
        return new ProjectOption(
                id, id.toUpperCase(), dir.toString(), false, false, null, null, true);
    }

    @Test
    void theActiveProjectIsNamedWithItsIdAndLinkForm() throws IOException {
        String text = service("kb").context("kb", List.of(span("kb", 1, 9)));

        assertThat(text).contains("project id `kb`").contains("/files?path=PATH&project=kb");
    }

    @Test
    void aLoneProjectGetsNoListOfOthers() throws IOException {
        String text = service("kb").context("kb", List.of(span("kb", 1, 9)));

        assertThat(text).doesNotContain("Other repositories");
    }

    @Test
    void everyOtherConfiguredProjectIsOfferedByItsId() throws IOException {
        String text = service("kb", "billing").context("kb", List.of(span("kb", 1, 9)));

        assertThat(text).contains("Other repositories").contains("`billing`");
    }

    @Test
    void aProjectTheChatWorkedOnEarlierIsMarkedAsSuch() throws IOException {
        String text =
                service("kb", "billing", "docs")
                        .context("kb", List.of(span("kb", 1, 4), span("billing", 5, 9)));

        assertThat(text).contains("`billing` — BILLING — this chat worked in it earlier");
        // Тот, где чат не был, перечислен без пометки — назвать его тоже можно.
        assertThat(text).contains("`docs` — DOCS").doesNotContain("`docs` — DOCS — this chat");
    }

    @Test
    void theActiveProjectIsNeverOfferedTwice() throws IOException {
        String text =
                service("kb", "billing")
                        .context(
                                "kb",
                                List.of(
                                        span("kb", 1, 4),
                                        span("billing", 5, 9),
                                        span("kb", 10, 12)));

        // В таймлайне активный проект есть и должен быть — а вот называть его в аргументе
        // `project` незачем: инструменты и так читают его по умолчанию.
        assertThat(offered(text)).doesNotContain("`kb`").contains("`billing`");
    }

    /** Часть текста после заголовка списка «что можно назвать» — только она про выбор id. */
    private static String offered(String text) {
        final int start = text.indexOf("Other repositories you may read");
        return start < 0 ? "" : text.substring(start);
    }

    /**
     * Уехавший из конфигурации проект в списке «что можно назвать» не появляется — {@code require}
     * на нём и падает.
     */
    @Test
    void anEarlierProjectThatIsNoLongerConfiguredIsNotOffered() throws IOException {
        String text =
                service("kb", "billing")
                        .context("kb", List.of(span("gone", 1, 4), span("kb", 5, 9)));

        assertThat(offered(text)).doesNotContain("`gone`");
    }

    // ── Таймлайн: где какой кусок чата прожит ────────────────────────────────

    /**
     * Чат, не менявший репозиторий, — это подавляющее большинство, и платить за таймлайн он не
     * должен: строка «messages 1-140» не сообщает ничего, кроме того, что и так сказано выше.
     */
    @Test
    void aChatThatNeverChangedRepositoryGetsNoTimeline() throws IOException {
        String text = service("kb", "billing").context("kb", List.of(span("kb", 1, 140)));

        assertThat(text).doesNotContain("Which messages belong where");
    }

    @Test
    void everyStretchIsListedWithItsMessageRange() throws IOException {
        String text =
                service("kb", "billing")
                        .context("billing", List.of(span("kb", 1, 34), span("billing", 35, 92)));

        assertThat(text).contains("Which messages belong where:");
        assertThat(text).contains("`kb` — KB — messages 1-34");
        // Последний отрезок открыт: чат в нём и продолжается.
        assertThat(text).contains("`billing` — BILLING — message 35 onward (the active project)");
    }

    /**
     * Возврат в прежний репозиторий — это третий отрезок, а не «уже был в списке». Свернув их, на
     * вопрос «где читан файл из сообщения 40» ответить было бы уже нечем.
     */
    @Test
    void returningToAProjectIsAThirdStretchNotADuplicate() throws IOException {
        String text =
                service("kb", "billing")
                        .context(
                                "kb",
                                List.of(
                                        span("kb", 1, 4),
                                        span("billing", 5, 9),
                                        span("kb", 10, 12)));

        assertThat(text)
                .contains("`kb` — KB — messages 1-4")
                .contains("`billing` — BILLING — messages 5-9")
                .contains("`kb` — KB — message 10 onward (the active project)");
    }

    /**
     * Открытая граница последнего отрезка в текст не попадает. Это половина контракта, который
     * держит блок неизменным внутри прогона: собирается он на каждой итерации tool-цикла, окно за
     * итерацию прирастает TOOL-рядами, и напечатанный верхний номер двигал бы текст последнего
     * вопроса на каждой из них — сбивая кэш промпта на ровном месте. Вторую половину (что кроме
     * этой границы ничего и не двигается) держит {@code ActiveProjectNoticeTest}.
     */
    @Test
    void theOpenEndOfTheLastStretchNeverReachesTheText() throws IOException {
        ProjectPromptService service = service("kb", "billing");

        String early =
                service.context("billing", List.of(span("kb", 1, 34), span("billing", 35, 40)));
        String later =
                service.context("billing", List.of(span("kb", 1, 34), span("billing", 35, 210)));

        assertThat(later).isEqualTo(early);
    }

    /** Отрезок в одно сообщение так и называется — «message 7», а не «messages 7-7». */
    @Test
    void aSingleMessageStretchIsNotWrittenAsARange() throws IOException {
        String text =
                service("kb", "billing")
                        .context("billing", List.of(span("kb", 7, 7), span("billing", 8, 20)));

        assertThat(text).contains("`kb` — KB — message 7\n");
    }

    /**
     * Проект, выбывший из конфигурации, в таймлайне остаётся: история и правда читана в нём, а
     * приписав те сообщения соседнему репозиторию, мы получили бы ссылки в чужое дерево.
     */
    @Test
    void aRetiredProjectStillOwnsItsStretch() throws IOException {
        String text =
                service("kb", "billing")
                        .context("kb", List.of(span("gone", 1, 4), span("kb", 5, 9)));

        assertThat(text).contains("`gone` — gone — messages 1-4");
    }
}
