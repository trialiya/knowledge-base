package io.github.trialiya.kb.repository;

import io.github.trialiya.kb.model.backfill.BackfillStateEntity;
import org.springframework.data.repository.CrudRepository;

/**
 * Маркеры выполненных run-once бэкфиллов (таблица {@code backfill_state}). Вызывающих сейчас нет:
 * механизм держится готовым под будущие миграции данных, см. {@link BackfillStateEntity}.
 */
public interface BackfillStateRepository extends CrudRepository<BackfillStateEntity, String> {}
