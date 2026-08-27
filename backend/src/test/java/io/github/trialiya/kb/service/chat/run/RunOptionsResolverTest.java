package io.github.trialiya.kb.service.chat.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.trialiya.kb.config.model.ChatModeProperties;
import io.github.trialiya.kb.config.model.ChatModelProperties;
import io.github.trialiya.kb.config.model.ChatModelProperties.ModelOption;
import io.github.trialiya.kb.config.model.GitProperties;
import io.github.trialiya.kb.config.model.ProjectProperties;
import io.github.trialiya.kb.config.model.ProjectProperties.ProjectOption;
import io.github.trialiya.kb.model.chat.entity.ChatTopicEntity;
import io.github.trialiya.kb.model.project.ProjectSwitch;
import io.github.trialiya.kb.repository.ChatTopicRepository;
import io.github.trialiya.kb.service.chat.prompt.ChatModeService;
import io.github.trialiya.kb.service.file.project.ProjectCatalog;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/**
 * Который репозиторий увидят инструменты прогона. Проверяется там, где параметр запроса, память
 * чата и список проектов сходятся в {@code RunOptions}: дальше проект едет уже как ключ {@code
 * ToolContext}, и ошибка здесь означает молчаливое чтение чужого репозитория, а не отказ.
 */
class RunOptionsResolverTest {

    private static final String CONV = "conv-1";
    private static final String USER = "anonymous";

    private ChatTopicRepository topicRepository;
    private RunOptionsResolver resolver;

    @BeforeEach
    void setUp() {
        topicRepository = mock(ChatTopicRepository.class);
        final ChatModeService modeService = mock(ChatModeService.class);
        when(modeService.instructionsFor(any())).thenReturn("");

        resolver =
                new RunOptionsResolver(
                        new ChatModelProperties(
                                new ModelOption("gpt", "GPT", true, null, null), List.of()),
                        new ChatModeProperties(List.of()),
                        modeService,
                        topicRepository,
                        catalog());
    }

    private static ProjectCatalog catalog() {
        return new ProjectCatalog(
                new ProjectProperties(
                        List.of(
                                new ProjectOption(
                                        "kb", "KB", "/srv/kb", false, false, null, null, true))),
                new GitProperties(null));
    }

    @Test
    void aChatThatNeverChoseRunsOnTheDefaultProject() {
        storedProject(null);

        assertThat(resolve(null).project()).isNull();
        verify(topicRepository, never()).updateProject(anyString(), anyString());
    }

    @Test
    void theChatsStoredProjectReachesTheRun() {
        storedProject("kb");

        assertThat(resolve(null).project()).isEqualTo("kb");
    }

    @Test
    void anExplicitProjectIsRememberedAsTheChatsChoice() {
        storedProject(null);

        assertThat(resolve("kb").project()).isEqualTo("kb");
        verify(topicRepository).updateProject(CONV, "kb");
    }

    /** Проект убрали из конфигурации — чат едет на дефолтном, а не падает на каждом сообщении. */
    @Test
    void aProjectDroppedFromTheConfigDegradesToTheDefault() {
        storedProject("retired");

        assertThat(resolve(null).project()).isNull();
    }

    @Test
    void anUnknownProjectInTheRequestIsRejected() {
        storedProject(null);

        assertThatThrownBy(() -> resolve("retired"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Unknown project");
    }

    /**
     * Не названный проект и явно названный дефолтный — один и тот же репозиторий: ни первый выбор
     * дефолта, ни его повторение сменой не считаются.
     */
    @Test
    void namingTheProjectTheChatAlreadyRunsOnIsNotASwitch() {
        storedProject(null);
        assertThat(resolve("kb").projectSwitch()).isNull();

        storedProject("kb");
        assertThat(resolve(null).projectSwitch()).isNull();
        assertThat(resolve("kb").projectSwitch()).isNull();
    }

    /**
     * Проект чата исчез из конфигурации — прогон уезжает на дефолтный, и это настоящая смена:
     * история читана в другом репозитории, и {@code from} называет его как есть, хоть
     * канонизировать выбывший id уже не во что.
     */
    @Test
    void aChatWhoseProjectWasRetiredSwitchesToTheDefault() {
        storedProject("retired");

        final ProjectSwitch projectSwitch = resolve(null).projectSwitch();

        assertThat(projectSwitch).isNotNull();
        assertThat(projectSwitch.from()).isEqualTo("retired");
        assertThat(projectSwitch.to()).isEqualTo("kb");
    }

    /**
     * И ровно на одном вопросе: маркер говорит «выше история читана в другом репозитории», а не
     * «этот чат когда-то выбрал выбывший проект». Поэтому уехавший на дефолтный чат приводит и
     * колонку к тому, на чём реально работает, — иначе каждое следующее сообщение сравнивалось бы с
     * тем же выбывшим значением и несло бы плашку заново.
     */
    @Test
    void aRetiredProjectIsMarkedOnceAndThenForgotten() {
        storedProject("retired");

        assertThat(resolve(null).projectSwitch()).isNotNull();
        verify(topicRepository).updateProject(CONV, null);

        storedProject(null); // колонку только что привели к дефолтному
        assertThat(resolve(null).projectSwitch()).isNull();
    }

    private ChatRunService.RunOptions resolve(@Nullable String requested) {
        return resolver.resolve(CONV, null, null, requested);
    }

    private void storedProject(@Nullable String project) {
        when(topicRepository.findById(CONV))
                .thenReturn(
                        Optional.of(
                                new ChatTopicEntity(
                                        CONV,
                                        USER,
                                        null,
                                        null,
                                        null,
                                        null,
                                        project,
                                        LocalDateTime.now(),
                                        LocalDateTime.now(),
                                        false)));
    }
}
