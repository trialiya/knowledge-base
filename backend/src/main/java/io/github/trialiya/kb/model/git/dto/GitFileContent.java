package io.github.trialiya.kb.model.git.dto;

/**
 * Содержимое файла из репозитория.
 *
 * @param path относительный путь
 * @param content текстовое содержимое (null для бинарных файлов)
 * @param binary true если файл бинарный
 * @param sizeBytes размер файла в байтах
 */
public record GitFileContent(String path, String content, boolean binary, long sizeBytes) {}
