package io.github.trialiya.kb.model.skill;

import io.github.trialiya.kb.model.tool.ToolCallResponseItem;
import io.github.trialiya.kb.model.tool.ToolCallResultMetaProvider;
import java.util.Map;

/**
 * Ответ {@code readSkill}: одна инструкция-навык целиком.
 *
 * <p>Поле с текстом называется {@code content} не случайно: режим «Обзор» в деталях вызова выбирает
 * вид по форме ответа ({@code resultViews/contentResult.js}), и длинная строка в {@code content} —
 * то, что превращает ответ в читаемый текстовый блок вместо сырого JSON.
 *
 * @param name имя навыка — из каталога в системном промпте или из списка проекта в блоке {@code
 *     <active-project>}
 * @param content текст инструкции как есть
 */
public record SkillContent(String name, String content)
        implements ToolCallResponseItem, ToolCallResultMetaProvider {

    /**
     * Гист — единственное, что из этого ответа увидит суммаризатор ({@code
     * SummarizeService.appendToolCalls}): сам текст навыка в сводку не попадает никогда. Поэтому
     * гист называет навык по имени — чтобы сводка могла зафиксировать, что он был загружен, и чат
     * после сжатия знал, что перечитать.
     */
    @Override
    public String getFormattedResponse() {
        return "навык " + name + " • " + content.length() + " симв.";
    }

    @Override
    public Map<String, Object> getResultMeta() {
        return Map.of("name", name, "contentChars", content.length());
    }
}
