package io.github.trialiya.kb.service.chat.run;

import io.github.trialiya.kb.model.chat.entity.RunTokenUsage;
import io.github.trialiya.kb.model.chat.entity.TokenUsage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/**
 * Токены, накопленные каждым идущим прогоном. Живёт отдельно от {@link ChatRunService} потому, что
 * считает их не он: обращения к модели видит {@code TokenUsageAdvisor} изнутри tool-цикла, а
 * заводит и закрывает прогон — сервис. Реестр — единственное, что их связывает.
 *
 * <p>Считаются только заведённые {@link #start} прогоны: карту чистит владелец прогона ({@code
 * ChatRunService.cleanup}), поэтому запись, заведённая по чужому {@code runId}, осталась бы в ней
 * навсегда. Размер карты печатает {@link ChatRuntimeMonitor} — в простое он обязан быть нулём.
 */
@Component
public class RunUsageRegistry {

    private final ConcurrentHashMap<String, AtomicReference<RunTokenUsage.Tally>> tallies =
            new ConcurrentHashMap<>();

    /** Начинает счёт для прогона. Обязательно закрыть {@link #forget} — иначе запись протечёт. */
    public void start(String runId) {
        tallies.put(runId, new AtomicReference<>(RunTokenUsage.Tally.EMPTY));
    }

    /** Считается ли этот прогон вообще (см. javadoc класса). */
    public boolean tracked(String runId) {
        return tallies.containsKey(runId);
    }

    /**
     * Итог прогона с учётом ещё не закрытого обращения к модели — то, что показывают по ходу
     * генерации. Ничего не накапливает: {@code pending} доедет до итога отдельно, через {@link
     * #add}, когда обращение закончится.
     */
    public RunTokenUsage snapshot(String runId, TokenUsage pending) {
        final AtomicReference<RunTokenUsage.Tally> ref = tallies.get(runId);
        return ref == null ? RunTokenUsage.EMPTY : ref.get().with(pending).view();
    }

    /** Накопленное прогоном; {@link RunTokenUsage#EMPTY} у незнакомого или уже закрытого. */
    public RunTokenUsage total(String runId) {
        return snapshot(runId, TokenUsage.EMPTY);
    }

    /**
     * Добавляет к итогу прогона замер одного законченного обращения к модели.
     *
     * @return новый итог прогона
     */
    public RunTokenUsage add(String runId, TokenUsage call) {
        final AtomicReference<RunTokenUsage.Tally> ref = tallies.get(runId);
        if (ref == null) {
            return RunTokenUsage.EMPTY;
        }
        return ref.updateAndGet(tally -> tally.with(call)).view();
    }

    /**
     * Снимает прогон с учёта.
     *
     * @return его итог — последняя возможность его прочитать
     */
    public RunTokenUsage forget(String runId) {
        final AtomicReference<RunTokenUsage.Tally> ref = tallies.remove(runId);
        return ref == null ? RunTokenUsage.EMPTY : ref.get().view();
    }

    /** Число прогонов на учёте — для мониторинга утечек (см. {@link ChatRuntimeMonitor}). */
    public int trackedRunCount() {
        return tallies.size();
    }
}
