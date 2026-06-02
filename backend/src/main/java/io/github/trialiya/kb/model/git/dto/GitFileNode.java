package io.github.trialiya.kb.model.git.dto;

import io.github.trialiya.kb.tools.ToolCallResponseItem;

/**
 * Один узел файлового дерева репозитория.
 *
 * @param path относительный путь от корня репозитория
 * @param name имя файла/каталога
 * @param type "file" или "directory"
 * @param size размер в байтах (только для файлов, у каталогов — null)
 */
public record GitFileNode(String path, String name, String type, Long size)
        implements ToolCallResponseItem {

    @Override
    public String getFormattedResponse() {
        return "directory".equals(type) ? path + "/" : path + " (" + size + "B)";
    }
}
