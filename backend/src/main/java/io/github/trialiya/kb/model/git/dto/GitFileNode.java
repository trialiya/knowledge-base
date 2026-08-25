package io.github.trialiya.kb.model.git.dto;

import io.github.trialiya.kb.model.tool.ToolCallResponseItem;
import io.github.trialiya.kb.model.tool.ToolCallResultMetaProvider;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Один узел файлового дерева репозитория.
 *
 * @param project id репозитория, к которому относится узел — обязателен в ответе, потому что {@code
 *     getFileTree} и {@code searchFiles} умеют смотреть не только в активный проект чата (см.
 *     {@code GitFunction}), и без эха модель не отличит, где лежит найденный путь
 * @param path относительный путь от корня репозитория
 * @param name имя файла/каталога
 * @param type тип записи (файл или директория)
 * @param size размер в байтах (только для файлов, у каталогов — null)
 * @param tracked отслеживается ли git. {@code false} — файл виден только через {@code
 *     kb.projects[].allow-globs} проекта: читать и править можно, но истории у него нет, он не
 *     попадёт в коммит, и создать рядом новый нельзя
 */
public record GitFileNode(
        String project,
        String path,
        String name,
        FileEntryType type,
        @Nullable Long size,
        boolean tracked)
        implements ToolCallResponseItem, ToolCallResultMetaProvider {

    /** Отслеживаемый узел — обычный случай, для него и есть этот конструктор. */
    public GitFileNode(
            String project, String path, String name, FileEntryType type, @Nullable Long size) {
        this(project, path, name, type, size, true);
    }

    @Override
    public String getFormattedResponse() {
        String suffix = tracked ? "" : " [untracked]";
        return type == FileEntryType.DIRECTORY
                ? path + "/" + suffix
                : path + " (" + size + "B)" + suffix;
    }

    @Override
    public Map<String, Object> getResultMeta() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("project", project);
        meta.put("path", path);
        meta.put("name", name);
        meta.put("sizeBytes", size);
        meta.put("type", type);
        meta.put("tracked", tracked);
        return meta;
    }
}
