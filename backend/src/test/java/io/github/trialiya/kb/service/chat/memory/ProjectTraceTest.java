package io.github.trialiya.kb.service.chat.memory;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import io.github.trialiya.kb.model.chat.entity.ChatMessageMeta;
import io.github.trialiya.kb.model.chat.entity.ProjectSpan;
import java.time.LocalDateTime;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.MessageType;

/**
 * Сборка следа проектов — то, чем чат отвечает на «в каком репозитории читан файл из сообщения 40»
 * после того, как само сообщение уехало из окна.
 *
 * <p>Один и тот же {@link ProjectTrace} работает на обеих сторонах: при сжатии он пишет спаны на
 * строку-сводку, при чтении собирает таймлайн для промпта. Поэтому дыра, перехлёст или потерянный
 * возврат в прежний проект — это сразу и неверная сводка, и неверный блок в промпте.
 */
class ProjectTraceTest {

    private static final String CONV = "conv-1";

    private static ChatMessageEntity row(long position, @Nullable ChatMessageMeta meta) {
        return new ChatMessageEntity(
                position,
                CONV,
                "text",
                MessageType.USER,
                position,
                false,
                false,
                LocalDateTime.now(),
                meta);
    }

    /** Базовый штамп первого сообщения: проект без «откуда». */
    private static ChatMessageEntity stamp(long position, String project) {
        return row(position, new ChatMessageMeta(null, false, List.of(), List.of(), project, null));
    }

    /** Маркер смены: этим сообщением чат перешёл из {@code from} в {@code to}. */
    private static ChatMessageEntity switched(long position, String from, String to) {
        return row(position, new ChatMessageMeta(null, false, List.of(), List.of(), to, from));
    }

    private static ChatMessageEntity plain(long position) {
        return row(position, null);
    }

    private static ChatMessageEntity summary(long position, List<ProjectSpan> spans) {
        return new ChatMessageEntity(
                position,
                CONV,
                "summary",
                MessageType.ASSISTANT,
                position,
                false,
                true,
                LocalDateTime.now(),
                ChatMessageMeta.ofProject(
                        spans.isEmpty() ? null : spans.getLast().project(), spans));
    }

    private static ProjectTrace trace(
            List<ChatMessageEntity> summaries, List<ChatMessageEntity> rows, long endPosition) {
        return ProjectTrace.of(summaries, rows, () -> null, endPosition);
    }

    @Test
    void aChatThatNeverSwitchedIsOneStretchFromItsBaseStamp() {
        ProjectTrace trace = trace(List.of(), List.of(stamp(1, "kb"), plain(2), plain(3)), 3);

        assertThat(trace.spans()).containsExactly(new ProjectSpan("kb", 1, 3));
        assertThat(trace.lastProject()).isEqualTo("kb");
    }

    /** Отрезок закрывается на сообщении ПЕРЕД маркером: маркер уже прочитан в новом репозитории. */
    @Test
    void aSwitchClosesThePreviousStretchOnTheMessageBeforeIt() {
        ProjectTrace trace =
                trace(
                        List.of(),
                        List.of(stamp(1, "kb"), plain(2), switched(3, "kb", "billing")),
                        5);

        assertThat(trace.spans())
                .containsExactly(new ProjectSpan("kb", 1, 2), new ProjectSpan("billing", 3, 5));
        assertThat(trace.lastProject()).isEqualTo("billing");
    }

    /** A→B→A — три отрезка. Свернув повторы, «где читан файл из сообщения 4» уже не ответить. */
    @Test
    void returningToAProjectOpensAThirdStretch() {
        ProjectTrace trace =
                trace(
                        List.of(),
                        List.of(
                                stamp(1, "kb"),
                                switched(3, "kb", "billing"),
                                switched(6, "billing", "kb")),
                        9);

        assertThat(trace.spans())
                .containsExactly(
                        new ProjectSpan("kb", 1, 2),
                        new ProjectSpan("billing", 3, 5),
                        new ProjectSpan("kb", 6, 9));
    }

    /** Отрезки смыкаются: между концом одного и началом следующего не должно быть ни дыры... */
    @Test
    void stretchesMeetWithoutGapsOrOverlaps() {
        List<ProjectSpan> spans =
                trace(
                                List.of(),
                                List.of(
                                        stamp(1, "kb"),
                                        switched(4, "kb", "billing"),
                                        switched(8, "billing", "docs")),
                                12)
                        .spans();

        for (int i = 1; i < spans.size(); i++) {
            assertThat(spans.get(i).from()).isEqualTo(spans.get(i - 1).to() + 1);
        }
        assertThat(spans.getFirst().from()).isEqualTo(1);
        assertThat(spans.getLast().to()).isEqualTo(12);
    }

    /** Следующая сводка наследует спаны предыдущей — за исчезнувшими маркерами тянуться не надо. */
    @Test
    void theNextSummaryInheritsTheSpansOfThePreviousOne() {
        ChatMessageEntity earlier = summary(40, List.of(new ProjectSpan("kb", 1, 40)));

        ProjectTrace trace = trace(List.of(earlier), List.of(plain(41), plain(42)), 80);

        assertThat(trace.spans()).containsExactly(new ProjectSpan("kb", 1, 80));
        assertThat(trace.lastProject()).isEqualTo("kb");
    }

    /** Смена внутри нового куска продлевает унаследованный отрезок ровно до себя. */
    @Test
    void aSwitchAfterASummaryExtendsTheInheritedStretchUpToIt() {
        ChatMessageEntity earlier = summary(40, List.of(new ProjectSpan("kb", 1, 40)));

        ProjectTrace trace =
                trace(List.of(earlier), List.of(plain(41), switched(50, "kb", "billing")), 80);

        assertThat(trace.spans())
                .containsExactly(new ProjectSpan("kb", 1, 49), new ProjectSpan("billing", 50, 80));
    }

    /**
     * Спаны берутся у ПОСЛЕДНЕЙ сводки: она накопительная, остальные — её подмножества. Иначе
     * collapse нескольких сводок в одну терял бы всё, что накопили промежуточные.
     */
    @Test
    void spansComeFromTheLastSummaryNotTheFirst() {
        ChatMessageEntity first = summary(40, List.of(new ProjectSpan("kb", 1, 40)));
        ChatMessageEntity last =
                summary(
                        80,
                        List.of(new ProjectSpan("kb", 1, 49), new ProjectSpan("billing", 50, 80)));

        ProjectTrace trace = trace(List.of(first, last), List.of(plain(81)), 90);

        assertThat(trace.spans())
                .containsExactly(new ProjectSpan("kb", 1, 49), new ProjectSpan("billing", 50, 90));
    }

    /** Одинокий {@code project} на сводке носителем не считается — иначе он открывал бы отрезок. */
    @Test
    void theLegacyScalarOnASummaryDoesNotOpenAStretch() {
        ChatMessageEntity legacy =
                new ChatMessageEntity(
                        40,
                        CONV,
                        "summary",
                        MessageType.ASSISTANT,
                        40,
                        false,
                        true,
                        LocalDateTime.now(),
                        ChatMessageMeta.ofProject("billing", List.of()));

        ProjectTrace trace = trace(List.of(legacy), List.of(stamp(41, "kb"), plain(42)), 42);

        assertThat(trace.spans()).containsExactly(new ProjectSpan("kb", 41, 42));
    }

    /** Носитель из уже накопленной части курсор назад не уводит. */
    @Test
    void carriersBelowTheInheritedCursorAreIgnored() {
        ChatMessageEntity earlier = summary(40, List.of(new ProjectSpan("billing", 1, 40)));

        ProjectTrace trace =
                trace(List.of(earlier), List.of(stamp(1, "kb"), plain(41), plain(42)), 42);

        assertThat(trace.spans()).containsExactly(new ProjectSpan("billing", 1, 42));
    }

    /** Носителя нет вовсе — отвечает проект чата, а спрашивают его только в этом случае. */
    @Test
    void withoutAnyCarrierTheChatsOwnProjectAnswers() {
        ProjectTrace trace =
                ProjectTrace.of(List.of(), List.of(plain(1), plain(2)), () -> "docs", 2);

        assertThat(trace.spans()).containsExactly(new ProjectSpan("docs", 1, 2));
        assertThat(trace.lastProject()).isEqualTo("docs");
    }

    @Test
    void withoutACarrierAndWithoutAChatProjectTheTraceIsEmpty() {
        ProjectTrace trace = trace(List.of(), List.of(plain(1), plain(2)), 2);

        assertThat(trace.spans()).isEmpty();
        assertThat(trace.lastProject()).isNull();
    }
}
