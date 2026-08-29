/**
 * История чата: окно, которое уходит модели, его сжатие и текст, в котором модель эту историю
 * видит.
 *
 * <p>Направление зависимостей внутри {@code service/chat} — {@code event} ← {@code runtime} ←
 * {@code memory} ← {@code run}. Одно ребро выходит за него: {@code memory} → {@code prompt}, ради
 * блока активного проекта ({@link io.github.trialiya.kb.service.chat.memory.ActiveProjectNotice}).
 * Цикла нет — {@code prompt} про историю не знает и получает уже готовые отрезки, — а собирать
 * текст в другом месте нельзя: единственная правда о том, что видит модель, это {@code
 * ChatHistoryService.promptRow}, и второй сборщик разошёлся бы с ним молча.
 */
@NullMarked
package io.github.trialiya.kb.service.chat.memory;

import org.jspecify.annotations.NullMarked;
