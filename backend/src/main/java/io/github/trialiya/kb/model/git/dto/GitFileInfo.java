package io.github.trialiya.kb.model.git.dto;

import org.jspecify.annotations.Nullable;

/**
 * Метаданные отслеживаемого файла без его содержимого: размер, бинарность, язык.
 *
 * <p>Существует ради {@code kb.stat}: скрипту нужно знать, текстовый файл или бинарный, ещё до
 * того, как он решит, чем его читать — {@code kb.read} или {@code kb.readBytes}. Ответ на этот
 * вопрос не должен стоить чтения самого файла.
 *
 * @param path относительный путь
 * @param sizeBytes размер файла в байтах
 * @param binary true если файл бинарный (эвристика по NUL-байту, как у git)
 * @param language язык программирования по расширению, либо null если не определён
 */
public record GitFileInfo(String path, long sizeBytes, boolean binary, @Nullable String language) {}
