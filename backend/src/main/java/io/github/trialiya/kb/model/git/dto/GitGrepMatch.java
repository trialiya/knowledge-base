package io.github.trialiya.kb.model.git.dto;

/**
 * Одно совпадение при поиске по содержимому tracked файлов (аналог строки вывода {@code git grep}).
 *
 * @param path относительный путь к файлу от корня репозитория
 * @param line номер строки (1-based)
 * @param text текст строки с совпадением (без ведущих/завершающих пробелов не обрезается — AI сам
 *     решает как обработать)
 */
public record GitGrepMatch(String path, int line, String text) {}
