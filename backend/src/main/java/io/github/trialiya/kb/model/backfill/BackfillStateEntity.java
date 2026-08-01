package io.github.trialiya.kb.model.backfill;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Одна строка = один выполненный run-once бэкфилл ({@code backfill_state}). Ключ — имя бэкфилла,
 * значение — момент успешного завершения. Наличие строки означает «уже сделано»: стартовая логика
 * (например, {@code ToolCallIdBackfillRunner}) пропускает прогон, не сканируя историю заново.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Table("backfill_state")
public class BackfillStateEntity {

    @Id private String name;

    private LocalDateTime doneAt;
}
