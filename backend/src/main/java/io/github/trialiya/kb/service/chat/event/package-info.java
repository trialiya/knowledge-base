/**
 * Транспорт событий чата: один хаб на чат, fan-out на все открытые вкладки и replay для
 * переподключения. Отдельно от {@code run} потому, что событиями пользуется не только прогон —
 * сжатие контекста, git-команды и очередь сообщений публикуют в те же хабы; вернув транспорт в
 * {@code run}, эти пакеты пришлось бы завязать на рантайм прогона ради одного {@code publish}.
 */
@NullMarked
package io.github.trialiya.kb.service.chat.event;

import org.jspecify.annotations.NullMarked;
