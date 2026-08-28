package io.github.trialiya.kb.service.chat.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trialiya.kb.model.chat.entity.TokenUsage;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;

/**
 * Область прогона и её реестр. Главное свойство — ничего не создаётся лениво: область заводит
 * только владелец прогона, поэтому опоздавшая запись не воскрешает состояние уже завершившегося
 * (иначе нумерация вызовов пошла бы с нуля посреди чужой ленты).
 */
class RunRegistryTest {

    private static final String CONV = "conv-1";
    private static final String RUN = "run-1";

    private final RunRegistry runs = new RunRegistry();

    private RunScope open(String runId, String conversationId) {
        return runs.open(runId, conversationId, "admin", "gpt-5");
    }

    @Test
    void aClosedRunIsSimplyNotFound() {
        open(RUN, CONV);
        assertThat(runs.find(RUN)).isPresent();

        runs.close(RUN);

        assertThat(runs.find(RUN)).isEmpty();
        assertThat(runs.find("no-such-run")).isEmpty();
        assertThat(runs.isEmpty()).isTrue();
        // Снять уже снятый — законный способ дойти до конца по второму пути.
        runs.close(RUN);
        assertThat(runs.size()).isZero();
    }

    /**
     * Снятая с учёта область продолжает жить у того, кто её держит: терминальная обработка
     * дописывает по ней ответ и токены уже после того, как прогон покинул реестр.
     */
    @Test
    void aClosedScopeStillCarriesWhatItCounted() {
        final RunScope scope = open(RUN, CONV);
        scope.addCall(new TokenUsage(100, 10, 110, 0, 0));

        runs.close(RUN);

        assertThat(scope.usage().contextTokens()).isEqualTo(110);
    }

    @Test
    void generatingInAnswersPerConversation() {
        open(RUN, CONV);

        assertThat(runs.generatingIn(CONV)).isTrue();
        assertThat(runs.generatingIn("conv-2")).isFalse();
    }

    /**
     * Остановку могут запросить, пока задача ещё не подписалась на стрим: флаг ставится до чтения
     * подписки, а подписка — до чтения флага, поэтому неостанавливаемым прогон не останется.
     */
    @Test
    void aStopRequestedBeforeTheSubscriptionStillDisposesIt() {
        final RunScope scope = open(RUN, CONV);
        final Recording subscription = new Recording();

        scope.cancel();
        scope.attach(subscription);

        assertThat(scope.stopRequested()).isTrue();
        assertThat(subscription.disposed).isTrue();
    }

    @Test
    void aStopAfterTheSubscriptionDisposesItToo() {
        final RunScope scope = open(RUN, CONV);
        final Recording subscription = new Recording();
        scope.attach(subscription);

        scope.cancel();

        assertThat(subscription.disposed).isTrue();
    }

    /** Терминальных сигналов у оборванного стрима бывает два — сохранить ответ вправе один. */
    @Test
    void onlyOneCallerMaySaveTheAnswer() {
        final RunScope scope = open(RUN, CONV);

        assertThat(scope.claimPersist()).isTrue();
        assertThat(scope.claimPersist()).isFalse();
    }

    /**
     * Номера сквозные по прогону и достаются даже скрытым инструментам — иначе они разошлись бы со
     * счётчиком коллектора, и фронт не склеил бы живую плашку с итоговой метой.
     */
    @Test
    void callIndexesRunThroughTheScopeAndStartOverInTheNext() {
        final RunScope first = open(RUN, CONV);

        assertThat(first.nextCallIndex()).isZero();
        assertThat(first.nextCallIndex()).isEqualTo(1);

        assertThat(open("run-2", CONV).nextCallIndex()).isZero();
    }

    @Test
    void anUnknownCallHasNothingRemembered() {
        final RunScope scope = open(RUN, CONV);
        scope.rememberCall("call-0", 3, Map.of("q", "a"));

        assertThat(scope.startedCall("call-0"))
                .isEqualTo(new RunScope.StartedCall(3, Map.of("q", "a")));
        assertThat(scope.startedCall("call-9")).isNull();
    }

    private static final class Recording implements Disposable {

        private boolean disposed;

        @Override
        public void dispose() {
            disposed = true;
        }

        @Override
        public boolean isDisposed() {
            return disposed;
        }
    }
}
