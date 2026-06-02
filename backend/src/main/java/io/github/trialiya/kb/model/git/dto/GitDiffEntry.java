package io.github.trialiya.kb.model.git.dto;

import io.github.trialiya.kb.tools.ToolCallResponseItem;

/**
 * Одна запись из diff коммита.
 *
 * @param status статус: A (added), M (modified), D (deleted), R (renamed) и т.д.
 * @param path путь к файлу (для переименований — новый путь)
 * @param oldPath старый путь (только при rename, иначе null)
 * @param additions количество добавленных строк
 * @param deletions количество удалённых строк
 * @param patch текстовый diff (unified), null если не запрашивался
 */
public record GitDiffEntry(
        String status, String path, String oldPath, int additions, int deletions, String patch)
        implements ToolCallResponseItem {

    @Override
    public String getFormattedResponse() {
        String loc = oldPath == null ? path : oldPath + "→" + path;
        return status + " " + loc + " (+" + additions + " -" + deletions + ")";
    }
}
