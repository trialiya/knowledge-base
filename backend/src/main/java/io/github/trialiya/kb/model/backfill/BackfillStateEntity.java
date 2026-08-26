package io.github.trialiya.kb.model.backfill;

import java.time.LocalDateTime;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.PersistenceCreator;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Одна строка = один выполненный run-once бэкфилл ({@code backfill_state}). Ключ — имя бэкфилла,
 * значение — момент успешного завершения. Наличие строки означает «уже сделано»: стартовый прогон
 * бэкфилла с этим именем пропускается, не сканируя данные заново.
 *
 * <p>Своих потребителей у таблицы сейчас нет — она с репозиторием держится под будущие миграции
 * данных: разовый проход по истории пишет сюда отметку и на следующем старте её видит. Новый
 * бэкфилл заводит своё имя и работает через {@code BackfillStateRepository}, схема не меняется.
 *
 * <p>Реализует {@link Persistable} с флагом {@code isNew}: без этого Spring Data JDBC считал бы
 * сущность с заполненным id уже существующей и {@code save()} делал бы {@code UPDATE}, а не {@code
 * INSERT} (тот же паттерн, что у {@code ChatTopicEntity}).
 */
@Table("backfill_state")
public class BackfillStateEntity implements Persistable<String> {

    @Id private final String name;
    private final LocalDateTime doneAt;
    @Transient private final boolean isNew;

    /** Канонический конструктор. */
    public BackfillStateEntity(String name, LocalDateTime doneAt, boolean isNew) {
        this.name = name;
        this.doneAt = doneAt;
        this.isNew = isNew;
    }

    /** Гидрация строки из БД. */
    @PersistenceCreator
    public BackfillStateEntity(String name, LocalDateTime doneAt) {
        this(name, doneAt, false);
    }

    public String getName() {
        return name;
    }

    public LocalDateTime getDoneAt() {
        return doneAt;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @Nullable
    @Override
    public String getId() {
        return name;
    }
}
