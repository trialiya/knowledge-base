/**
 * История чата: окно, которое уходит модели, его сжатие и текст, в котором модель эту историю
 * видит.
 *
 * <p>Направление зависимостей внутри {@code service/chat} — {@code event} ← {@code runtime} ←
 * {@code memory} ← {@code run}. Наружу за пределы этой цепочки идёт несколько рёбер — все туда,
 * откуда история и её сжатие берут готовый материал, а не наоборот: {@code prompt} (блок активного
 * проекта в {@link io.github.trialiya.kb.service.chat.memory.ActiveProjectNotice}, подстановки
 * {@code sys.md} для запроса сжатия), {@code tools} (схемы инструментов в том же запросе), {@code
 * advisor} и {@code functions} (замер и поиск по истории у обоих раундов сжатия). Цикла нет — ни
 * один из этих пакетов про историю не знает и получает уже готовые отрезки или готовый ответ, — а
 * собирать текст истории в другом месте нельзя: единственная правда о том, что видит модель, это
 * {@code ChatHistoryService.promptRow}, и второй сборщик разошёлся бы с ним молча.
 */
@NullMarked
package io.github.trialiya.kb.service.chat.memory;

import org.jspecify.annotations.NullMarked;
