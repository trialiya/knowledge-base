package io.github.trialiya.kb.service.chat.memory;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import io.github.trialiya.kb.model.chat.entity.ChatMessageMeta;
import io.github.trialiya.kb.model.chat.entity.GitEventMeta;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.MessageType;

/**
 * Деление живого окна между {@code /compact} и {@code /compact-1}: где проходит граница
 * сбережённого хода и когда сжимать нечего.
 *
 * <p>Главное здесь — что «последний ход» считается тем же правилом, что и везде в чате ({@code
 * ChatHistoryService.tailAfterLastUser}): ход не открывают ни ряд события, ни вопрос, доставленный
 * посреди прогона, и сберечь по ним значило бы разрезать чужой ход пополам.
 */
class CompactWindowTest {

    private static final String CONV = "conv-1";

    /** Без флага делить нечего: сводке уходит всё окно, живого хвоста не остаётся. */
    @Test
    void aFullCompactionKeepsNothing() {
        final List<ChatMessageEntity> live = turns(3);

        final CompactWindow window = CompactWindow.of(live, false);

        assertThat(window.compacted()).isEqualTo(live);
        assertThat(window.kept()).isEmpty();
    }

    /** Сбережённый ход — последний вопрос и всё, что модель по нему наработала. */
    @Test
    void theLastTurnIsKeptWithEverythingTheRunProduced() {
        final List<ChatMessageEntity> live = turns(3);

        final CompactWindow window = CompactWindow.of(live, true);

        assertThat(positionsOf(window.compacted())).containsExactly(0L, 1L, 2L, 3L, 4L, 5L);
        assertThat(positionsOf(window.kept())).containsExactly(6L, 7L, 8L);
    }

    /**
     * Ряд события (git-команда, откат правок) ход не открывает: сбереги его границей — и ответ,
     * лежащий выше него, остался бы без своего вопроса.
     */
    @Test
    void anEventRowDoesNotOpenATurn() {
        final List<ChatMessageEntity> live = new ArrayList<>(turns(2));
        live.add(gitEventRow(6));

        final CompactWindow window = CompactWindow.of(live, true);

        assertThat(positionsOf(window.kept())).containsExactly(3L, 4L, 5L, 6L);
    }

    /** Вопрос, доставленный посреди прогона, ход тоже не открывает — его открыл вопрос выше. */
    @Test
    void anInterjectionDoesNotOpenATurn() {
        final List<ChatMessageEntity> live = new ArrayList<>(turns(2));
        live.add(interjectionRow(6));

        final CompactWindow window = CompactWindow.of(live, true);

        assertThat(positionsOf(window.kept())).containsExactly(3L, 4L, 5L, 6L);
    }

    /**
     * Единственный ход в окне — сжимать до него нечего, и это тот же отказ, что у {@code /compact}
     * по чату из одной сводки.
     */
    @Test
    void aWindowOfOneTurnHasNothingToCompactWhenThatTurnIsKept() {
        final List<ChatMessageEntity> live = turns(1);

        assertThat(CompactWindow.of(live, true).isEmpty()).isTrue();
        assertThat(CompactWindow.of(live, false).isEmpty()).isFalse();
    }

    /** Одни сводки до сбережённого хода — тоже «нечего»: сводка в сводку ничего не экономит. */
    @Test
    void aHeadOfSummariesAloneIsNothingToCompact() {
        final List<ChatMessageEntity> live = new ArrayList<>();
        live.add(summaryRow(0));
        live.addAll(turns(1));

        assertThat(CompactWindow.of(live, true).isEmpty()).isTrue();
    }

    /**
     * Хода в окне нет вовсе — беречь нечего, и деление выходит таким же, как у {@code /compact}.
     * Что из этого следует для плашки, решает уже {@code CompactService.commandTarget}.
     */
    @Test
    void aWindowWithoutASingleTurnKeepsNothing() {
        final List<ChatMessageEntity> live = List.of(summaryRow(0), gitEventRow(1));

        final CompactWindow window = CompactWindow.of(live, true);

        assertThat(window.compacted()).isEqualTo(live);
        assertThat(window.kept()).isEmpty();
    }

    // -------------------------------------------------------------------------

    private static List<Long> positionsOf(List<ChatMessageEntity> rows) {
        return rows.stream().map(ChatMessageEntity::getPosition).toList();
    }

    /** Ходы по три позиции: вопрос, ответ модели и пустая протокольная TOOL-строка за ним. */
    private static List<ChatMessageEntity> turns(int count) {
        final List<ChatMessageEntity> rows = new ArrayList<>();
        for (int turn = 0; turn < count; turn++) {
            rows.add(row(turn * 3, MessageType.USER, "question " + turn, null));
            rows.add(row(turn * 3 + 1, MessageType.ASSISTANT, "answer " + turn, null));
            rows.add(row(turn * 3 + 2, MessageType.TOOL, "", null));
        }
        return rows;
    }

    private static ChatMessageEntity gitEventRow(long position) {
        return row(
                position,
                MessageType.USER,
                "",
                ChatMessageMeta.ofGitEvent(new GitEventMeta("commit", "kb", true, "", null)));
    }

    private static ChatMessageEntity interjectionRow(long position) {
        return row(position, MessageType.USER, "и ещё", ChatMessageMeta.ofInterjection(List.of()));
    }

    private static ChatMessageEntity summaryRow(long position) {
        return new ChatMessageEntity(
                position + 1,
                CONV,
                "earlier summary",
                MessageType.ASSISTANT,
                position,
                false,
                true,
                LocalDateTime.now(),
                null);
    }

    private static ChatMessageEntity row(
            long position, MessageType type, String content, ChatMessageMeta meta) {
        return new ChatMessageEntity(
                position + 1,
                CONV,
                content,
                type,
                position,
                false,
                false,
                LocalDateTime.now(),
                meta);
    }
}
