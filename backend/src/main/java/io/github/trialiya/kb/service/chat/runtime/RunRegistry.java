package io.github.trialiya.kb.service.chat.runtime;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Идущие прогоны — по одной области ({@link RunScope}) на каждый. Заводит и закрывает области
 * владелец прогона ({@code ChatRunService}), остальные только ищут: advisor считает токены, запись
 * истории нумерует вызовы инструментов, контроллер спрашивает, идёт ли ещё генерация.
 *
 * <p>Лениво здесь не создаётся ничего — и это главное свойство реестра. Область, заведённая не
 * владельцем, осталась бы в карте навсегда (закрыть её было бы некому), а заведённая ПОСЛЕ
 * завершения прогона — воскресила бы состояние мёртвого: нумерация вызовов начала бы отсчёт заново
 * посреди чужой ленты. Поэтому «не нашёл» значит «прогона больше нет», и это законный ответ.
 *
 * <p>Размер карты печатает {@code ChatRuntimeMonitor} — в простое он обязан быть нулём.
 */
@Component
public class RunRegistry {

    private final ConcurrentHashMap<String, RunScope> byRunId = new ConcurrentHashMap<>();

    /**
     * Заводит область прогона. Звать только владельцу и ровно один раз на {@code runId} —
     * обязательно закрыв её потом {@link #close}, иначе запись протечёт.
     */
    public RunScope open(String runId, String conversationId, String user, String model) {
        final RunScope scope = new RunScope(runId, conversationId, user, model);
        byRunId.put(runId, scope);
        return scope;
    }

    /** Живой прогон по id; пусто — такого прогона нет (или он уже закончился). */
    public Optional<RunScope> find(String runId) {
        return Optional.ofNullable(byRunId.get(runId));
    }

    /**
     * Снимает прогон с учёта.
     *
     * @return его область — последняя возможность прочитать накопленное; {@code null}, если прогон
     *     сняли раньше
     */
    public @Nullable RunScope close(String runId) {
        return byRunId.remove(runId);
    }

    /** Генерирует ли в этом чате хоть какой-нибудь прогон. */
    public boolean generatingIn(String conversationId) {
        return byRunId.values().stream()
                .anyMatch(scope -> scope.conversationId().equals(conversationId));
    }

    /** Снимок всех идущих прогонов — для остановки приложения. */
    public List<RunScope> all() {
        return List.copyOf(byRunId.values());
    }

    public boolean isEmpty() {
        return byRunId.isEmpty();
    }

    /** Число прогонов в реестре — для мониторинга утечек (см. {@code run.ChatRuntimeMonitor}). */
    public int size() {
        return byRunId.size();
    }
}
