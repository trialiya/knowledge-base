package io.github.trialiya.kb.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.trialiya.kb.support.TestProjects;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The routing every caller depends on while nothing selects a project yet: an unnamed project is
 * the default one, an unknown one is refused, and "may the model write here" is answered in one
 * place for the edit tools and for scripts alike.
 */
class GitRegistryTest {

    @TempDir Path repoDir;

    /**
     * Второй репозиторий — свой каталог; {@code git init} в нём зовут только те тесты, кому он
     * нужен рабочим, остальным он изображает не доехавший mount.
     */
    @TempDir Path secondRepo;

    @BeforeEach
    void initRepo() {
        runGit("init", "-q");
    }

    @Test
    void namingNoProjectGivesTheDefaultRepository() {
        GitRegistry registry = TestProjects.registry(repoDir, false);

        assertThat(registry.forProject(null)).isSameAs(registry.defaultProject());
        assertThat(registry.forProject(TestProjects.ID)).isSameAs(registry.defaultProject());
        assertThat(registry.defaultProject().project().path())
                .isEqualTo(repoDir.toAbsolutePath().normalize());
    }

    @Test
    void namingNoProjectAndNamingTheDefaultOneAreTheSameProject() {
        GitRegistry registry = TestProjects.registry(repoDir, false);

        // What tells "the model named another repository" from "it named this one": a chat that
        // stored no project still runs on the default one, so the raw values differ but the
        // project does not.
        assertThat(registry.sameProject(null, TestProjects.ID)).isTrue();
        assertThat(registry.sameProject(TestProjects.ID, null)).isTrue();
        assertThat(registry.sameProject(null, null)).isTrue();
    }

    @Test
    void anUnknownProjectIsRefused() {
        GitRegistry registry = TestProjects.registry(repoDir, false);

        assertThatThrownBy(() -> registry.forProject("billing"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("billing");
    }

    @Test
    void aReadOnlyProjectOffersNoWrites() {
        GitRegistry registry = TestProjects.registry(repoDir, false);

        assertThat(registry.editsAllowed(null)).isFalse();
        assertThat(registry.anyEditable()).isFalse();
        assertThatThrownBy(() -> registry.requireEditable(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(TestProjects.ID);
    }

    @Test
    void aWritableProjectWithEditsOnAcceptsThem() {
        GitRegistry registry = TestProjects.registry(repoDir, true);

        assertThat(registry.editsAllowed(null)).isTrue();
        assertThat(registry.anyEditable()).isTrue();
        assertThat(registry.requireEditable(null)).isSameAs(registry.defaultProject());
    }

    // ── Несколько проектов ───────────────────────────────────────────────────

    @Test
    void everyConfiguredProjectGetsARepositoryOfItsOwn() {
        runGit(secondRepo, "init", "-q");
        GitRegistry registry =
                TestProjects.registry(
                        List.of(
                                TestProjects.project("kb", repoDir),
                                TestProjects.project("billing", secondRepo)));

        assertThat(registry.forProject("billing")).isNotSameAs(registry.forProject("kb"));
        assertThat(registry.defaultProject()).isSameAs(registry.forProject("kb"));
        assertThat(registry.forProject("billing").project().path())
                .isEqualTo(secondRepo.toAbsolutePath().normalize());
        assertThat(registry.sameProject("kb", "billing")).isFalse();
    }

    /** Не доехавший mount стоит своего проекта — не сервера: остальные репозитории работают. */
    @Test
    void aProjectWhoseRepositoryIsMissingIsRefusedByNameWhileTheRestServe() {
        GitRegistry registry =
                TestProjects.registry(
                        List.of(
                                TestProjects.project("kb", repoDir),
                                TestProjects.project("billing", secondRepo))); // git init не звали

        assertThat(registry.defaultProject().project().path())
                .isEqualTo(repoDir.toAbsolutePath().normalize());
        assertThat(registry.editsAllowed("billing")).isFalse();
        assertThatThrownBy(() -> registry.forProject("billing"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("billing")
                .hasMessageContaining("unavailable");
    }

    /**
     * Что видит селектор: список, дефолтный пункт и — главное — доступность. Настроенный, но не
     * открывшийся проект остаётся в списке помеченным: спрятать его значило бы сказать «такого
     * проекта нет», а чат, который его выбрал, ответил бы совсем другой репозиторий.
     */
    @Test
    void theSelectorIsToldWhichEntryIsPreselectedAndWhichIsUnavailable() {
        GitRegistry registry =
                TestProjects.registry(
                        List.of(
                                TestProjects.project("kb", repoDir),
                                TestProjects.project("billing", secondRepo))); // git init не звали

        assertThat(registry.options().defaultProject()).isEqualTo("kb");
        assertThat(registry.options().projects())
                .satisfiesExactly(
                        p -> {
                            assertThat(p.id()).isEqualTo("kb");
                            assertThat(p.label()).isEqualTo("kb");
                            assertThat(p.available()).isTrue();
                        },
                        p -> {
                            assertThat(p.id()).isEqualTo("billing");
                            assertThat(p.available()).isFalse();
                        });
    }

    /** Дефолтный — исключение: без него не работает ничего, и это честный отказ старта. */
    @Test
    void aMissingDefaultRepositoryStillFailsStartup() {
        assertThatThrownBy(
                        () ->
                                TestProjects.registry(
                                        List.of(
                                                TestProjects.project("billing", secondRepo),
                                                TestProjects.project("kb", repoDir))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("billing");
    }

    private void runGit(String... args) {
        runGit(repoDir, args);
    }

    private void runGit(Path dir, String... args) {
        try {
            String[] command = new String[args.length + 1];
            command[0] = "git";
            System.arraycopy(args, 0, command, 1, args.length);
            Process process =
                    new ProcessBuilder(command)
                            .directory(dir.toFile())
                            .redirectErrorStream(true)
                            .start();
            if (process.waitFor() != 0) {
                throw new IllegalStateException("git " + String.join(" ", args) + " failed");
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
