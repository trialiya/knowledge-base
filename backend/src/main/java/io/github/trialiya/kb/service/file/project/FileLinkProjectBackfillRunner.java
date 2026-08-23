package io.github.trialiya.kb.service.file.project;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * Run-once бэкафилл ссылок на файлы, записанных без проекта (см. {@link
 * FileLinkProjectBackfillService}). Вызывается при каждом старте, реальную работу делает один раз:
 * маркер {@value FileLinkProjectBackfillService#KEY} в {@code backfill_state} ставится в той же
 * транзакции.
 */
@Slf4j
@AllArgsConstructor
@Component
public class FileLinkProjectBackfillRunner implements CommandLineRunner {

    private final FileLinkProjectBackfillService backfillService;

    @Override
    public void run(String... args) {
        int updated = backfillService.stampProjectInStoredLinksIfNeeded();
        if (updated > 0) {
            log.info("File-link project backfill: {} row(s) stamped", updated);
        }
    }
}
