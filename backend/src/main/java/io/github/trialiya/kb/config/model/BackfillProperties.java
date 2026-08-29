package io.github.trialiya.kb.config.model;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Настройки разовых проходов по истории. Bound from {@code kb.chat.backfill}.
 *
 * @param dumpPath каталог, куда проход кладёт снимок переписываемых им данных ПЕРЕД первой записью.
 *     Пустой путь означает «дампу негде лежать», и проход тогда не выполняется вовсе: переписывать
 *     чужую историю без возможности вернуть её обратно — не та цена, которую стоит платить молча.
 *     Чтение это переживает (см. {@code ProjectStampBackfill}), поэтому отказ безопасен.
 */
@ConfigurationProperties(prefix = "kb.chat.backfill")
public record BackfillProperties(@Nullable String dumpPath) {}
