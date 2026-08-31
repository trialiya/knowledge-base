package io.github.trialiya.kb.model.git.dto;

/**
 * Одна замена текста в терминах, в которых её делает {@code editFile}: точное вхождение {@code
 * oldString} меняется на {@code newString}, единственное — или все сразу при {@code replaceAll}.
 *
 * <p>Появилась ради отката правок ответа ({@code ChatFileRevert}): откат — это те же замены
 * наоборот, поэтому ему нужен способ передать их пачкой, а не по одному вызову инструмента.
 */
public record TextEdit(String oldString, String newString, boolean replaceAll) {}
