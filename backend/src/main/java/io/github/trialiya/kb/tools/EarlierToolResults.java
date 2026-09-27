package io.github.trialiya.kb.tools;

import io.github.trialiya.kb.model.tool.ToolData;
import java.util.List;

/**
 * Результаты инструментов, которые модель видит в промпте этого чата прямо сейчас: ответы из
 * живого, несжатого окна истории, в том числе прошлых ходов. Нужны guard'ам записи, которым мало
 * истории одного прогона ({@link ToolInvocationCollector}): документ, прочитанный ходом раньше и с
 * тех пор не менявшийся, перечитывать незачем — его текст и так лежит перед моделью.
 *
 * <p>Только живое окно, не вся история: сжатые ряды модель видит пересказом, а не текстом, и чтение
 * из них за чтение не считается. Реализация — {@code ChatHistoryService#liveToolResponses}.
 */
@FunctionalInterface
public interface EarlierToolResults {

    /** Никакой истории — для инструментов вне чата (фоновые задачи, тесты). */
    EarlierToolResults NONE = conversationId -> List.of();

    /** Ответы инструментов живого окна чата, от старых к новым. */
    List<ToolData.Response> of(String conversationId);
}
