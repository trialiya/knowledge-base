package io.github.trialiya.kb.service.chat.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import io.github.trialiya.kb.model.chat.entity.ChatMessageMeta;
import io.github.trialiya.kb.model.chat.entity.ChatTopicEntity;
import io.github.trialiya.kb.model.chat.entity.ProjectSpan;
import io.github.trialiya.kb.repository.BackfillStateRepository;
import io.github.trialiya.kb.repository.ChatMessageRepository;
import io.github.trialiya.kb.repository.ChatTopicRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.MessageType;

/**
 * Разовый проход, который приводит старые чаты к тому виду, в каком их пишет новый код: базовый
 * штамп на первом вопросе, спаны на строках-сводках.
 *
 * <p>Ради чего он вообще есть, видно как раз здесь: после прохода у чтения остаётся ровно одна
 * форма ответа на «где прожит этот кусок истории» — спаны. Не будь прохода, {@code
 * ActiveProjectNotice} пришлось бы вечно поддерживать ещё две.
 */
class ProjectStampBackfillTest {

    private static final String CONV = "conv-1";

    private ChatMessageRepository messageRepo;
    private ChatTopicRepository topicRepo;
    private ProjectStampBackfill backfill;

    @BeforeEach
    void setUp() {
        messageRepo = mock(ChatMessageRepository.class);
        topicRepo = mock(ChatTopicRepository.class);
        backfill =
                new ProjectStampBackfill(
                        messageRepo, topicRepo, mock(BackfillStateRepository.class));
    }

    private static ChatMessageEntity row(
            long position, MessageType type, boolean summary, @Nullable ChatMessageMeta meta) {
        return new ChatMessageEntity(
                position,
                CONV,
                "text",
                type,
                position,
                summary,
                summary,
                LocalDateTime.now(),
                meta);
    }

    private static ChatMessageEntity question(long position) {
        return row(position, MessageType.USER, false, null);
    }

    private static ChatMessageEntity switched(long position, String from, String to) {
        return row(
                position,
                MessageType.USER,
                false,
                new ChatMessageMeta(null, false, List.of(), List.of(), to, from));
    }

    private static ChatMessageEntity legacySummary(long position, @Nullable String project) {
        return row(
                position,
                MessageType.ASSISTANT,
                true,
                ChatMessageMeta.ofProject(project, List.of()));
    }

    private void stored(ChatMessageEntity... rows) {
        when(messageRepo.findByConversationIdOrderByPositionAsc(CONV)).thenReturn(List.of(rows));
    }

    private void chatProject(@Nullable String project) {
        when(topicRepo.findById(CONV))
                .thenReturn(
                        Optional.of(
                                new ChatTopicEntity(
                                        CONV,
                                        "admin",
                                        null,
                                        null,
                                        null,
                                        null,
                                        project,
                                        LocalDateTime.now(),
                                        LocalDateTime.now(),
                                        false)));
    }

    private List<ChatMessageEntity> saved() {
        @SuppressWarnings("unchecked")
        final ArgumentCaptor<Iterable<ChatMessageEntity>> captor =
                ArgumentCaptor.forClass(Iterable.class);
        org.mockito.Mockito.verify(messageRepo).saveAll(captor.capture());
        final List<ChatMessageEntity> rows = new ArrayList<>();
        captor.getValue().forEach(rows::add);
        return rows;
    }

    /** Чат без смен: базой становится текущий проект чата — история вся на нём и прошла. */
    @Test
    void aChatThatNeverSwitchedIsStampedWithItsOwnProject() {
        stored(question(1), question(3));
        chatProject("billing");

        assertThat(backfill.backfill(CONV)).isEqualTo(1);
        assertThat(saved())
                .singleElement()
                .satisfies(
                        row -> {
                            assertThat(row.getPosition()).isEqualTo(1);
                            assertThat(row.getMeta().project()).isEqualTo("billing");
                            assertThat(row.getMeta().projectSwitchFrom()).isNull();
                        });
    }

    /**
     * Чат со сменой: базой становится {@code from} первой смены, а не текущий проект. Взяв текущий,
     * мы бы приписали начало истории репозиторию, в котором её не читали.
     */
    @Test
    void theBaseComesFromTheFirstSwitchNotFromTheChatsCurrentProject() {
        stored(question(1), switched(5, "kb", "billing"));
        chatProject("billing");

        backfill.backfill(CONV);

        assertThat(saved())
                .singleElement()
                .satisfies(row -> assertThat(row.getMeta().project()).isEqualTo("kb"));
    }

    /** Второй прогон ничего не меняет: штамп уже стоит, и переписывать его нечем. */
    @Test
    void aChatAlreadyStampedIsLeftAlone() {
        stored(
                row(
                        1,
                        MessageType.USER,
                        false,
                        new ChatMessageMeta(null, false, List.of(), List.of(), "kb", null)),
                question(2));
        chatProject("kb");

        assertThat(backfill.backfill(CONV)).isZero();
    }

    /** Проекта у чата нет вовсе — штамповать нечем, дефолтный из каталога и так верен. */
    @Test
    void aChatWithoutAProjectIsNotStamped() {
        stored(question(1), question(2));
        when(topicRepo.findById(any())).thenReturn(Optional.empty());

        assertThat(backfill.backfill(CONV)).isZero();
    }

    /** Сводка получает спаны — накопительно, ровно как их пишет очередной раунд сжатия. */
    @Test
    void everySummaryRowGetsTheSpansOfEverythingAboveIt() {
        stored(
                question(1),
                legacySummary(40, "kb"),
                switched(50, "kb", "billing"),
                legacySummary(80, "billing"),
                question(81));
        chatProject("billing");

        backfill.backfill(CONV);

        final List<ChatMessageEntity> saved = saved();
        assertThat(saved).hasSize(3);
        assertThat(saved.get(1).getMeta().visitedProjects())
                .containsExactly(new ProjectSpan("kb", 1, 40));
        assertThat(saved.get(2).getMeta().visitedProjects())
                .containsExactly(new ProjectSpan("kb", 1, 49), new ProjectSpan("billing", 50, 80));
    }

    /** Одинокий {@code project} остаётся на месте: откат приложения читает свои сводки по нему. */
    @Test
    void theLegacyScalarSurvivesTheBackfill() {
        stored(question(1), legacySummary(40, "kb"));
        chatProject("kb");

        backfill.backfill(CONV);

        assertThat(saved().get(1).getMeta().project()).isEqualTo("kb");
    }
}
