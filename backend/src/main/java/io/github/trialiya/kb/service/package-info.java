/**
 * Бизнес-логика, разложенная по предметным модулям: {@code chat} (разговор — его рантайм в {@code
 * chat.run}, история в {@code chat.memory}, песочница инструмента {@code runScript} в {@code
 * chat.script}, тексты промпта в {@code chat.prompt}, вложения в {@code chat.context}, сам чат и
 * поиск по чатам в {@code chat.topic}), {@code document} (дерево документов, экспорт и импорт),
 * {@code file} (настроенные проекты в {@code file.project}, рабочее дерево в {@code file.git},
 * структура файла в {@code file.outline}) и {@code embedding} (векторы и семантический поиск).
 *
 * <p>Здесь, на верхнем уровне, остаётся только то, что не принадлежит ни одному из них: сервис,
 * которому нужны сразу несколько модулей, — как {@code SearchAgentService}, соединяющий поиск по
 * репозиторию с поиском по базе знаний.
 */
@NullMarked
package io.github.trialiya.kb.service;

import org.jspecify.annotations.NullMarked;
