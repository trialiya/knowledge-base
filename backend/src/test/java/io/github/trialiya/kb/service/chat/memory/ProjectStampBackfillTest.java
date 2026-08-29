package io.github.trialiya.kb.service.chat.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.trialiya.kb.config.model.BackfillProperties;
import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import io.github.trialiya.kb.model.chat.entity.ChatMessageMeta;
import io.github.trialiya.kb.model.chat.entity.ChatTopicEntity;
import io.github.trialiya.kb.model.chat.entity.ProjectSpan;
import io.github.trialiya.kb.repository.BackfillStateRepository;
import io.github.trialiya.kb.repository.ChatMessageRepository;
import io.github.trialiya.kb.repository.ChatTopicRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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

    @TempDir Path dumpDir;

    private ChatMessageRepository messageRepo;
    private ChatTopicRepository topicRepo;
    private BackfillStateRepository stateRepo;
    private SummaryWriter summaryWriter;
    private ProjectStampDump dump;
    private ProjectStampBackfill backfill;

    @BeforeEach
    void setUp() throws IOException {
        messageRepo = mock(ChatMessageRepository.class);
        topicRepo = mock(ChatTopicRepository.class);
        stateRepo = mock(BackfillStateRepository.class);
        summaryWriter = mock(SummaryWriter.class);
        // Замок мока сам ничего не выполняет, а проход всю работу делает внутри него.
        doAnswer(
                        call -> {
                            call.getArgument(1, Runnable.class).run();
                            return null;
                        })
                .when(summaryWriter)
                .inConversation(any(), any());
        dump = ProjectStampDump.open(dumpDir, new ObjectMapper());
        backfill = backfillWith(dumpDir.toString());
    }

    private ProjectStampBackfill backfillWith(@Nullable String dumpPath) {
        return new ProjectStampBackfill(
                messageRepo,
                topicRepo,
                stateRepo,
                summaryWriter,
                new ObjectMapper(),
                new BackfillProperties(dumpPath));
    }

    @AfterEach
    void tearDown() throws IOException {
        dump.close();
    }

    /** Один чат — так, как его гоняет проход: со снимком, но без замка (он у мока пустой). */
    private int backfill(String conversationId) {
        return backfill.backfill(conversationId, dump);
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

        assertThat(backfill(CONV)).isEqualTo(1);
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

        backfill(CONV);

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

        assertThat(backfill(CONV)).isZero();
    }

    /** Проекта у чата нет вовсе — штамповать нечем, дефолтный из каталога и так верен. */
    @Test
    void aChatWithoutAProjectIsNotStamped() {
        stored(question(1), question(2));
        when(topicRepo.findById(any())).thenReturn(Optional.empty());

        assertThat(backfill(CONV)).isZero();
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

        backfill(CONV);

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

        backfill(CONV);

        assertThat(saved().get(1).getMeta().project()).isEqualTo("kb");
    }

    /**
     * Поле, о котором проход ничего не знает, он не стирает. Сейчас {@code SummaryWriter} не кладёт
     * на сводку ничего, кроме следа проектов, — но ряды, по которым проход идёт, писали версии,
     * которых уже нет, и собранная заново мета молча теряла бы их содержимое.
     */
    @Test
    void aFieldTheBackfillDoesNotComputeIsLeftOnTheSummaryRow() {
        stored(
                question(1),
                row(
                        40,
                        MessageType.ASSISTANT,
                        true,
                        new ChatMessageMeta(
                                "run-7", false, List.of(), List.of(), "kb", null, "gpt-5")));
        chatProject("kb");

        backfill(CONV);

        assertThat(saved().get(1).getMeta())
                .satisfies(
                        meta -> {
                            assertThat(meta.visitedProjects()).isNotEmpty();
                            assertThat(meta.runId()).isEqualTo("run-7");
                            assertThat(meta.model()).isEqualTo("gpt-5");
                        });
    }

    // ── Снимок и обход целиком ───────────────────────────────────────────────

    /**
     * Снимок ложится на диск ДО записи: восстанавливать по нему придётся как раз тогда, когда
     * проход оборвался посередине, а до закрытия файла в этом случае дело не дойдёт.
     */
    @Test
    void theSnapshotHitsTheDiskBeforeTheRowsAreRewritten() throws IOException {
        stored(question(1), legacySummary(40, "kb"));
        chatProject("kb");
        when(messageRepo.saveAll(any()))
                .thenAnswer(
                        call -> {
                            assertThat(dumpFile()).hasLineCount(2);
                            return List.of();
                        });

        backfill(CONV);

        verify(messageRepo).saveAll(any());
        // Текста переписки в снимке нет, а прежнее значение меты — есть: по нему и восстанавливают.
        assertThat(dumpFile()).doesNotContain("\"text\"").contains("\\\"project\\\":\\\"kb\\\"");
    }

    private String dumpFile() throws IOException {
        try (var files = Files.list(dumpDir)) {
            return Files.readString(files.findFirst().orElseThrow());
        }
    }

    /** Без каталога под снимок проход не идёт вовсе — и отметку «сделано» не ставит. */
    @Test
    void withoutADumpDirectoryNothingIsRewritten() {
        backfillWith(" ").backfillIfNeeded();

        verify(topicRepo, never()).findAllConversationIds();
        verify(stateRepo, never()).save(any());
    }

    /**
     * Сорвавшийся чат не забирает с собой остальные. Их проход доделывает, а отметку не ставит:
     * поставь он её — чаты за сорвавшимся остались бы без следа проектов навсегда.
     */
    @Test
    void oneFailingChatDoesNotStopThePassAndLeavesItUnmarked() {
        when(topicRepo.findAllConversationIds()).thenReturn(List.of("bad", CONV));
        when(messageRepo.findByConversationIdOrderByPositionAsc("bad"))
                .thenThrow(new IllegalStateException("meta of an unknown shape"));
        stored(question(1), question(2));
        chatProject("kb");

        backfill.backfillIfNeeded();

        assertThat(saved())
                .singleElement()
                .satisfies(r -> assertThat(r.getPosition()).isEqualTo(1));
        verify(stateRepo, never()).save(any());
    }

    /** Прошли все — отметка ставится, и следующий старт данные уже не трогает. */
    @Test
    void aCleanPassIsMarkedDone() {
        when(topicRepo.findAllConversationIds()).thenReturn(List.of(CONV));
        stored(question(1), question(2));
        chatProject("kb");

        backfill.backfillIfNeeded();

        verify(stateRepo).save(any());
    }

    /**
     * Каждый чат переписывается под замком этого чата: раунд сжатия, попавший в секунды прохода,
     * иначе записал бы сводку по ещё не размеченным данным — и с обрезанным следом навсегда.
     */
    @Test
    void everyChatIsRewrittenUnderItsOwnLock() {
        when(topicRepo.findAllConversationIds()).thenReturn(List.of(CONV));
        stored(question(1), question(2));
        chatProject("kb");

        backfill.backfillIfNeeded();

        verify(summaryWriter).inConversation(eq(CONV), any());
    }
}
