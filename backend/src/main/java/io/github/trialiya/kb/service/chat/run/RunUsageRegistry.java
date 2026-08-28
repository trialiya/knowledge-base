package io.github.trialiya.kb.service.chat.run;

import io.github.trialiya.kb.model.chat.dto.TokenUsage;
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

    private final ConcurrentHashMap<String, AtomicReference<TokenUsage>> totals =
            new ConcurrentHashMap<>();

    /** Начинает счёт для прогона. Обязательно закрыть {@link #forget} — иначе запись протечёт. */
    public void start(String runId) {
        totals.put(runId, new AtomicReference<>(TokenUsage.EMPTY));
    }

    /** Считается ли этот прогон вообще (см. javadoc класса). */
    public boolean tracked(String runId) {
        return totals.containsKey(runId);
    }

    /** Накопленное прогоном; {@link TokenUsage#EMPTY} у незнакомого или уже закрытого прогона. */
    public TokenUsage total(String runId) {
        final AtomicReference<TokenUsage> ref = totals.get(runId);
        return ref == null ? TokenUsage.EMPTY : ref.get();
    }

    /**
     * Добавляет к итогу прогона замер одного обращения к модели.
     *
     * @return новый итог прогона
     */
    public TokenUsage add(String runId, TokenUsage iteration) {
        final AtomicReference<TokenUsage> ref = totals.get(runId);
        if (ref == null) {
            return TokenUsage.EMPTY;
        }
        return iteration.isEmpty() ? ref.get() : ref.accumulateAndGet(iteration, TokenUsage::plus);
    }

    /**
     * Снимает прогон с учёта.
     *
     * @return его итог — последняя возможность его прочитать
     */
    public TokenUsage forget(String runId) {
        final AtomicReference<TokenUsage> ref = totals.remove(runId);
        return ref == null ? TokenUsage.EMPTY : ref.get();
    }

    /** Число прогонов на учёте — для мониторинга утечек (см. {@link ChatRuntimeMonitor}). */
    public int trackedRunCount() {
        return totals.size();
    }
}
