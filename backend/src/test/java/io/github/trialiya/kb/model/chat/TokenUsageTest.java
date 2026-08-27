package io.github.trialiya.kb.model.chat;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trialiya.kb.model.chat.dto.TokenUsage;
import org.junit.jupiter.api.Test;

/**
 * Правила свёртки замеров. Тест перебирает именно те формы, в которых usage приходит от разных
 * провайдеров: единственный финальный чанк, нарастающий итог в каждом чанке и prompt/completion,
 * разнесённые по разным чанкам. Свёртка обязана давать одну и ту же цифру во всех трёх.
 */
class TokenUsageTest {

    @Test
    void anAllZeroMeasurementIsEmpty() {
        assertThat(TokenUsage.EMPTY.isEmpty()).isTrue();
        assertThat(new TokenUsage(0, 0, 0, 0, 1).isEmpty()).isFalse();
    }

    @Test
    void aSingleFinalChunkSurvivesTheMerge() {
        final TokenUsage merged = TokenUsage.EMPTY.merge(new TokenUsage(100, 20, 120, 0, 0));

        assertThat(merged).isEqualTo(new TokenUsage(100, 20, 120, 0, 0));
    }

    @Test
    void aRunningTotalKeepsTheLastChunk() {
        final TokenUsage merged =
                TokenUsage.EMPTY
                        .merge(new TokenUsage(100, 5, 105, 0, 0))
                        .merge(new TokenUsage(100, 20, 120, 0, 0));

        assertThat(merged).isEqualTo(new TokenUsage(100, 20, 120, 0, 0));
    }

    /** «Взять первый непустой» потерял бы completion, «взять последний» — prompt. */
    @Test
    void promptAndCompletionSplitAcrossChunksAddUp() {
        final TokenUsage merged =
                TokenUsage.EMPTY
                        .merge(new TokenUsage(100, 0, 100, 0, 0))
                        .merge(new TokenUsage(0, 20, 20, 0, 0));

        assertThat(merged).isEqualTo(new TokenUsage(100, 20, 120, 0, 0));
    }

    /** Итог провайдера больше суммы частей (reasoning-токены) — нормализация его не срезает. */
    @Test
    void aProviderTotalAboveThePartsIsKept() {
        final TokenUsage merged = TokenUsage.EMPTY.merge(new TokenUsage(100, 20, 200, 0, 0));

        assertThat(merged.totalTokens()).isEqualTo(200);
    }

    @Test
    void iterationsAddUp() {
        final TokenUsage total =
                new TokenUsage(100, 20, 120, 10, 5).plus(new TokenUsage(300, 40, 340, 90, 0));

        assertThat(total).isEqualTo(new TokenUsage(400, 60, 460, 100, 5));
    }

    /** Прогон с инструментами — это несколько обращений к модели, и prompt в каждом свой. */
    @Test
    void mergeInsideAnIterationAndSumBetweenThem() {
        final TokenUsage first = TokenUsage.EMPTY.merge(new TokenUsage(100, 10, 110, 0, 0));
        final TokenUsage second =
                TokenUsage.EMPTY
                        .merge(new TokenUsage(400, 5, 405, 0, 0))
                        .merge(new TokenUsage(400, 30, 430, 0, 0));

        assertThat(first.plus(second)).isEqualTo(new TokenUsage(500, 40, 540, 0, 0));
    }
}
