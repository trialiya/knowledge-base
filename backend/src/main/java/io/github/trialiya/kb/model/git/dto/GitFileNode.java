package io.github.trialiya.kb.model.git.dto;

import io.github.trialiya.kb.model.tool.ToolCallResponseItem;
import io.github.trialiya.kb.model.tool.ToolCallResultMetaProvider;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Один узел файлового дерева репозитория.
 *
 * @param path относительный путь от корня репозитория
 * @param name имя файла/каталога
 * @param type "file" или "directory"
 * @param size размер в байтах (только для файлов, у каталогов — null)
 */
public record GitFileNode(String path, String name, String type, @Nullable Long size)
        implements ToolCallResponseItem, ToolCallResultMetaProvider {

    @Override
    public String getFormattedResponse() {
        return "directory".equals(type) ? path + "/" : path + " (" + size + "B)";
    }

    @Override
    public Map<String, Object> getResultMeta() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("path", path);
        meta.put("name", name);
        meta.put("sizeBytes", size);
        meta.put("type", type);
        return meta;
    }
}
