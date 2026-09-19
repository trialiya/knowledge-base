package io.github.trialiya.kb.service.chat.memory;

import io.github.trialiya.kb.service.chat.memory.ChatHistoryService.PromptRow;
import java.util.List;

/**
 * Как сжатие делит живое окно: что уезжает в сводку и что остаётся жить дальше. Отдельно от {@code
 * CompactService} затем, что это правило про историю, а не про раунд: сам раунд одинаков у обоих
 * делений и про повод не знает вовсе.
 *
 * <p>Делений два, и различает их одна команда. {@code /compact} отдаёт сводке всё окно целиком —
 * после него живого разговора не остаётся. {@code /compact-1} оставляет живым последний ход:
 * вопрос, которым он открыт, и всё, что модель на него наработала. Ход — та же граница, по которой
 * её ищут все остальные ({@link ChatHistoryService#tailAfterLastUser}), и второй копии этого
 * правила заводить нельзя: разойдясь, они дали бы «последний ход» в двух разных местах истории.
 *
 * <p>Зачем оставлять: сводка пересказывает разговор, а не воспроизводит его, и дороже всего этот
 * пересказ обходится самому свежему материалу — прочитанным файлам, выводу команд, найденным
 * фрагментам, с которыми работа ещё идёт. {@code /compact-1} сжимает всё, что успело устареть, и
 * оставляет в исходном виде ровно то, что нужно следующему вопросу.
 *
 * @param compacted ряды, которые уедут модели на сжатие и после него перестанут ехать ей вовсе
 * @param kept живой хвост — остаётся в контексте как есть; пустой у {@code /compact}
 */
public record CompactWindow(List<PromptRow> compacted, List<PromptRow> kept) {

    /**
     * Делит живое окно надвое.
     *
     * @param live живое окно БЕЗ строки самой команды (см. {@link
     *     ChatHistoryService#promptRowsBefore}): команда — сигнал к сжатию, а не его материал, и
     *     ходом, который бережёт {@code keepLastRun}, она не бывает
     * @param keepLastRun оставить последний ход живым. Хода в окне может не оказаться вовсе (одни
     *     сводки и ряды событий) — тогда беречь нечего, и деление выходит таким же, как у {@code
     *     /compact}: что из этого следует для плашки, решает {@code CompactService.commandTarget}
     */
    public static CompactWindow of(List<PromptRow> live, boolean keepLastRun) {
        if (!keepLastRun) {
            return new CompactWindow(live, List.of());
        }
        int lastTurn = -1;
        for (int i = 0; i < live.size(); i++) {
            if (ChatHistoryService.opensATurn(live.get(i).entity())) {
                lastTurn = i;
            }
        }
        if (lastTurn < 0) {
            return new CompactWindow(live, List.of());
        }
        return new CompactWindow(live.subList(0, lastTurn), live.subList(lastTurn, live.size()));
    }

    /** Сжимать в этом делении нечего — см. {@link #nothingToCompact(List)}. */
    public boolean isEmpty() {
        return nothingToCompact(compacted);
    }

    /**
     * Сжимать нечего, когда живого контекста нет вовсе или он уже состоит из одной сводки: сжатие
     * сводки в сводку — это раунд, который ничего не экономит и при этом теряет детали.
     */
    static boolean nothingToCompact(List<PromptRow> rows) {
        return rows.stream().filter(row -> !row.entity().isSummary()).findAny().isEmpty();
    }
}
