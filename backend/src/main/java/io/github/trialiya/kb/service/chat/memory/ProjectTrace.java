package io.github.trialiya.kb.service.chat.memory;

import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import io.github.trialiya.kb.model.chat.entity.ChatMessageMeta;
import io.github.trialiya.kb.model.chat.entity.ProjectSpan;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * Где какой кусок чата прожит: хронологические отрезки {@link ProjectSpan} и проект, на котором
 * кусок закончился.
 *
 * <p>Один алгоритм на обе стороны. При сжатии {@link #of} собирает то, что осядет на строке-сводке
 * ({@code SummaryWriter}); при чтении — то, что уедет модели блоком активного проекта ({@link
 * ActiveProjectNotice}). Второй копии этой сборки быть не должно: разойдясь, они назвали бы модели
 * разные репозитории для одних и тех же сообщений, причём молча.
 *
 * <p>Носители следа в живой истории — вопросы пользователя с {@code meta.project}: базовый штамп
 * первого сообщения и маркеры смены (см. {@link ChatMessageMeta}). Всё, что было до них, приходит
 * спанами последней сводки: она наследует спаны предыдущей, поэтому одной строки хватает на всю
 * сжатую историю и тянуться за исчезнувшими маркерами не приходится.
 */
public record ProjectTrace(List<ProjectSpan> spans, @Nullable String lastProject) {

    /**
     * Собирает след по сводкам окна и живым рядам.
     *
     * @param summaries строки-сводки окна в хронологическом порядке; спаны берутся у последней —
     *     она накопительная, остальные её подмножества
     * @param rows живые ряды в порядке позиций, БЕЗ строк-сводок: одинокий {@code project} на
     *     сводке — прежний вид того же следа, и посчитанный носителем он открыл бы отрезок там, где
     *     чат никуда не переходил
     * @param leadingProject проект начала истории — {@code chat_topic.project}; спрашивается лениво
     *     и только когда носителя в начале нет вовсе, то есть у чата, начатого прежней версией. У
     *     остальных первое сообщение штамповано, и лишнего запроса на итерацию tool-цикла не будет
     * @param endPosition позиция, которой закрывается последний отрезок
     */
    public static ProjectTrace of(
            List<ChatMessageEntity> summaries,
            List<ChatMessageEntity> rows,
            Supplier<@Nullable String> leadingProject,
            long endPosition) {
        final List<ProjectSpan> spans = new ArrayList<>(inherited(summaries));
        @Nullable String current;
        long from;
        if (spans.isEmpty()) {
            from = rows.isEmpty() ? 1 : rows.getFirst().getPosition();
            current = rows.isEmpty() ? null : carrier(rows.getFirst());
            if (current == null) {
                current = leadingProject.get();
            }
        } else {
            current = spans.getLast().project();
            from = spans.getLast().to() + 1;
        }
        for (ChatMessageEntity row : rows) {
            // Ряды из уже накопленных отрезков пропускаем: вызывающий вправе передать окно целиком,
            // а не только новый кусок, и носитель из сжатой части иначе увёл бы курсор назад.
            if (row.getPosition() < from) {
                continue;
            }
            final @Nullable String carrier = carrier(row);
            if (carrier == null || carrier.equals(current)) {
                continue;
            }
            // Отрезок закрывается на сообщении ПЕРЕД сменой: маркер стоит на первом вопросе,
            // прочитанном уже в новом репозитории. Пустой отрезок не пишется — так бывает, когда
            // смена случилась сразу за границей сжатия.
            if (current != null && from < row.getPosition()) {
                spans.add(new ProjectSpan(current, from, row.getPosition() - 1));
            }
            current = carrier;
            from = row.getPosition();
        }
        if (current != null && from <= endPosition) {
            spans.add(new ProjectSpan(current, from, endPosition));
        }
        return new ProjectTrace(merged(spans), current);
    }

    private static List<ProjectSpan> inherited(List<ChatMessageEntity> summaries) {
        if (summaries.isEmpty()) {
            return List.of();
        }
        final @Nullable ChatMessageMeta meta = summaries.getLast().getMeta();
        return meta == null ? List.of() : meta.visitedProjects();
    }

    private static @Nullable String carrier(ChatMessageEntity row) {
        final @Nullable ChatMessageMeta meta = row.getMeta();
        return meta == null ? null : meta.project();
    }

    /**
     * Склеивает соседние отрезки одного проекта. Нужно на каждом стыке: хвост наследованных спанов
     * и первый отрезок нового куска почти всегда один и тот же репозиторий — чат сменил проект
     * ровно там, где сменил, а не там, где прошло сжатие.
     */
    private static List<ProjectSpan> merged(List<ProjectSpan> spans) {
        final List<ProjectSpan> out = new ArrayList<>(spans.size());
        for (ProjectSpan span : spans) {
            if (!out.isEmpty() && out.getLast().project().equals(span.project())) {
                final ProjectSpan previous = out.removeLast();
                out.add(new ProjectSpan(previous.project(), previous.from(), span.to()));
            } else {
                out.add(span);
            }
        }
        return List.copyOf(out);
    }
}
