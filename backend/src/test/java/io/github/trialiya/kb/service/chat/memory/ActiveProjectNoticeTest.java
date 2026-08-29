package io.github.trialiya.kb.service.chat.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import io.github.trialiya.kb.model.chat.entity.ChatMessageMeta;
import io.github.trialiya.kb.model.chat.entity.ChatTopicEntity;
import io.github.trialiya.kb.model.chat.entity.GitEventMeta;
import io.github.trialiya.kb.model.chat.entity.ProjectSpan;
import io.github.trialiya.kb.repository.ChatTopicRepository;
import io.github.trialiya.kb.service.chat.prompt.ProjectPromptService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.MessageType;

/**
 * Куда садится блок активного проекта и что в нём оказывается.
 *
 * <p>Место здесь важнее текста: блок обязан стоять ровно на одном ряду и ровно на том, который
 * открывает текущий ход. Копия на каждом вопросе — это лишний текст в каждом запросе; блок на ряду
 * из середины истории — прямая неправда, там активным был другой репозиторий.
 */
class ActiveProjectNoticeTest {

    private static final String CONV = "conv-1";

    private ProjectPromptService projectPrompt;
    private ChatTopicRepository chatTopicRepository;
    private ActiveProjectNotice notice;

    @BeforeEach
    void setUp() {
        projectPrompt = mock(ProjectPromptService.class);
        chatTopicRepository = mock(ChatTopicRepository.class);
        when(projectPrompt.context(any(), any())).thenReturn("### Active project");
        notice = new ActiveProjectNotice(projectPrompt, chatTopicRepository);
    }

    private static ChatMessageEntity row(
            long position, MessageType type, @Nullable ChatMessageMeta meta) {
        return new ChatMessageEntity(
                position, CONV, "text", type, position, false, false, LocalDateTime.now(), meta);
    }

    private static ChatMessageEntity question(long position) {
        return row(position, MessageType.USER, null);
    }

    private static ChatMessageEntity stamp(long position, String project) {
        return row(
                position,
                MessageType.USER,
                new ChatMessageMeta(null, false, List.of(), List.of(), project, null));
    }

    private static ChatMessageEntity answer(long position) {
        return row(position, MessageType.ASSISTANT, null);
    }

    private static ChatMessageEntity gitEvent(long position) {
        return row(
                position,
                MessageType.USER,
                ChatMessageMeta.ofGitEvent(new GitEventMeta("pull", "kb", true, "ok", "main")));
    }

    private static ChatMessageEntity interjection(long position) {
        return row(position, MessageType.USER, ChatMessageMeta.ofInterjection(List.of()));
    }

    @Test
    void theAnchorIsTheLastQuestionOfTheWindow() {
        assertThat(ActiveProjectNotice.anchor(List.of(question(1), answer(2), question(3))))
                .isEqualTo(3);
    }

    /** Оба ряда прозрачны для «последнего вопроса» — ход открыл не они. */
    @Test
    void neitherAGitEventNorAnInterjectionBecomesTheAnchor() {
        assertThat(
                        ActiveProjectNotice.anchor(
                                List.of(question(3), answer(4), gitEvent(5), interjection(6))))
                .isEqualTo(3);
    }

    /** Ставить некуда — окно из одних ответов; собирать блок тогда незачем вовсе. */
    @Test
    void aWindowWithoutAQuestionHasNoAnchor() {
        assertThat(ActiveProjectNotice.anchor(List.of(answer(1), answer(2)))).isEqualTo(-1);
    }

    @Test
    void theNoticeIsTaggedAndTellsTheSummarizerToDropIt() {
        String text = notice.render(CONV, List.of(stamp(1, "kb"), answer(2)));

        assertThat(text).startsWith("<active-project>\n").endsWith("</active-project>");
        assertThat(text).contains("do not preserve it");
    }

    /** Активный проект — последний носитель окна, а не первый и не проект чата. */
    @Test
    void theActiveProjectIsTheLastCarrierOfTheWindow() {
        notice.render(
                CONV,
                List.of(
                        stamp(1, "kb"),
                        row(
                                4,
                                MessageType.USER,
                                new ChatMessageMeta(
                                        null, false, List.of(), List.of(), "billing", "kb"))));

        ArgumentCaptor<String> project = ArgumentCaptor.forClass(String.class);
        verify(projectPrompt).context(project.capture(), any());
        assertThat(project.getValue()).isEqualTo("billing");
    }

    @Test
    void theTimelineCarriesEveryStretchOfTheWindow() {
        notice.render(
                CONV,
                List.of(
                        stamp(1, "kb"),
                        row(
                                4,
                                MessageType.USER,
                                new ChatMessageMeta(
                                        null, false, List.of(), List.of(), "billing", "kb")),
                        answer(5)));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ProjectSpan>> spans = ArgumentCaptor.forClass(List.class);
        verify(projectPrompt).context(any(), spans.capture());
        assertThat(spans.getValue())
                .containsExactly(new ProjectSpan("kb", 1, 3), new ProjectSpan("billing", 4, 5));
    }

    /** Носителя нет — отвечает {@code chat_topic}: чат, начатый git-командой, тоже должен знать. */
    @Test
    void aWindowWithoutACarrierFallsBackToTheChatsProject() {
        when(chatTopicRepository.findById(CONV))
                .thenReturn(
                        Optional.of(
                                new ChatTopicEntity(
                                        CONV,
                                        "admin",
                                        null,
                                        null,
                                        null,
                                        null,
                                        "docs",
                                        LocalDateTime.now(),
                                        LocalDateTime.now(),
                                        false)));

        notice.render(CONV, List.of(gitEvent(1), question(2)));

        ArgumentCaptor<String> project = ArgumentCaptor.forClass(String.class);
        verify(projectPrompt).context(project.capture(), any());
        assertThat(project.getValue()).isEqualTo("docs");
    }

    /**
     * У окна с носителем чат не спрашивается вовсе: {@code promptRows} зовётся на каждой итерации
     * tool-цикла, и лишний запрос там платился бы каждым прогоном.
     */
    @Test
    void aWindowWithACarrierNeverQueriesTheChat() {
        notice.render(CONV, List.of(stamp(1, "kb"), answer(2)));

        verify(chatTopicRepository, never()).findById(any());
    }
}
