package io.github.trialiya.kb.model.tool;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.jspecify.annotations.Nullable;

/**
 * Ответ читающего инструмента вместе с общими для всего ответа параметрами: {@code project} и, у
 * выдачи с пределом, {@code truncated}.
 *
 * <p>Обёртка, а не поле в каждом элементе выдачи: репозиторий у вызова один — «один вызов, один
 * репозиторий» (см. {@code sys.md}, раздел «Reading another project»), — и повторять его в каждом
 * из пятисот узлов дерева значит платить контекстом за то, что сказано строкой выше.
 *
 * <p>Разворачивает её {@code RecordingToolCallback}: сводка и метаданные вызова считаются по {@link
 * #result}, то есть по той же форме, что и у инструментов без обёртки. Модель же видит обёртку как
 * есть — поле {@code project} верхнего уровня, ровно как у {@code ScriptResult} и {@code
 * SearchAgentResult}, которые несут его собственным полем и в обёртке не нуждаются.
 *
 * <p>REST-контроллеры отдают DTO без обёртки: там репозиторий назвал сам вызывающий, параметром
 * запроса.
 *
 * @param project канонический id ответившего репозитория
 * @param result собственно ответ — список или одиночный DTO
 * @param truncated только у выдачи, которую обрезал предел: {@code true} — дальше могли быть ещё
 *     совпадения, и пустой или короткий список не значит «больше нет». У ответа без предела поля нет
 *     вовсе, а не {@code false}: модели незачем читать его в каждом дереве и файле
 */
public record ToolResult<T>(
        String project,
        T result,
        @Nullable @JsonInclude(JsonInclude.Include.NON_NULL) Boolean truncated) implements ProjectScoped {

    public ToolResult(String project, T result) {
        this(project, result, null);
    }
}
