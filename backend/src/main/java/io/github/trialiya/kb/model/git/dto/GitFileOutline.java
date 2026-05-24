package io.github.trialiya.kb.model.git.dto;

import java.util.List;

/**
 * Структурный обзор файла: список символов верхнего уровня без полного содержимого. Позволяет ИИ
 * понять устройство файла и затем точечно прочитать нужный диапазон строк через getFileContent.
 *
 * @param path относительный путь
 * @param language определённый язык, либо null
 * @param lineCount общее количество строк
 * @param parser имя использованного парсера: "tree-sitter" или "regex" (фолбэк)
 * @param symbols список символов в порядке появления в файле
 */
public record GitFileOutline(
        String path, String language, int lineCount, String parser, List<GitSymbol> symbols) {}
