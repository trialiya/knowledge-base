/**
 * Разговор целиком: {@code run} — рантайм прогона, {@code memory} — история и вызовы инструментов,
 * {@code script} — песочница инструмента {@code runScript}, {@code prompt} — тексты, из которых
 * собирается промпт, {@code context} — вложения и опись приложенного, {@code topic} — сам чат и
 * поиск по чатам.
 *
 * <p>Здесь остаётся только то, что не принадлежит ни одной из этих частей: {@code
 * ToolCatalogService} описывает не разговор, а набор инструментов, который панели настроек нужно
 * показать ровно таким, каким его видит модель.
 */
@NullMarked
package io.github.trialiya.kb.service.chat;

import org.jspecify.annotations.NullMarked;
