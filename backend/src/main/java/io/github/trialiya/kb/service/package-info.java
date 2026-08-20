/**
 * Бизнес-логика, разложенная по предметным модулям: {@code chat} (разговор — его рантайм в {@code
 * chat.run}, история в {@code chat.memory}, песочница инструмента {@code runScript} в {@code
 * chat.script}), {@code document} (дерево документов, экспорт и импорт), {@code file} (репозитории
 * проектов и чтение их файлов) и {@code embedding} (векторы и семантический поиск).
 *
 * <p>Здесь, на верхнем уровне, остаётся только то, что не принадлежит ни одному из них: сервис,
 * которому нужны сразу несколько модулей, — как {@code SearchAgentService}, соединяющий поиск по
 * репозиторию с поиском по базе знаний.
 */
@NullMarked
package io.github.trialiya.kb.service;

import org.jspecify.annotations.NullMarked;
