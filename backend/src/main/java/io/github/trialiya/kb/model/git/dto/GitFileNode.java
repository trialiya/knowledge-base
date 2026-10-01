package io.github.trialiya.kb.model.git.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonView;
import io.github.trialiya.kb.model.tool.OmitTrue;
import io.github.trialiya.kb.model.tool.ToolCallResponseItem;
import io.github.trialiya.kb.model.tool.ToolCallResultMetaProvider;
import io.github.trialiya.kb.model.tool.ToolJson;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Один узел файлового дерева репозитория.
 *
 * <p>Репозиторий узел не называет: он один на всю выдачу и назван обёрткой ответа ({@code
 * ToolResult}).
 *
 * @param path относительный путь от корня репозитория
 * @param name имя файла/каталога — последний сегмент {@code path}; модели не печатается (см. {@link
 *     ToolJson}), дерево «Файлов» подписывает им узел
 * @param type тип записи (файл или директория)
 * @param size размер в байтах (только для файлов, у каталогов — null)
 * @param tracked отслеживается ли git; {@code true} в JSON не печатается (см. {@link OmitTrue}). {@code false} — файл виден только через {@code
 *     kb.projects[].allow-globs} проекта: читать и править можно, но истории у него нет, он не
 *     попадёт в коммит, и создать рядом новый нельзя
 */
public record GitFileNode(
        String path,
        @JsonView(ToolJson.UiOnly.class) String name,
        FileEntryType type,
        @Nullable @JsonInclude(JsonInclude.Include.NON_NULL) Long size,

        @JsonInclude(value = JsonInclude.Include.CUSTOM, valueFilter = OmitTrue.class)
        boolean tracked)
        implements ToolCallResponseItem, ToolCallResultMetaProvider {

    /** Отслеживаемый узел — обычный случай, для него и есть этот конструктор. */
    public GitFileNode(String path, String name, FileEntryType type, @Nullable Long size) {
        this(path, name, type, size, true);
    }

    @Override
    public String getFormattedResponse() {
        String suffix = tracked ? "" : " [untracked]";
        return type == FileEntryType.DIRECTORY ? path + "/" + suffix : path + " (" + size + "B)" + suffix;
    }

    @Override
    public Map<String, Object> getResultMeta() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("path", path);
        meta.put("name", name);
        meta.put("sizeBytes", size);
        meta.put("type", type);
        meta.put("tracked", tracked);
        return meta;
    }
}
