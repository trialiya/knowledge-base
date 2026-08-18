package io.github.trialiya.kb.service;

import io.github.trialiya.kb.model.backfill.BackfillStateEntity;
import io.github.trialiya.kb.repository.BackfillStateRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Run-once бэкафилл: проставляет проект в ссылках на файлы, записанных до того, как ссылки стали
 * его нести (см. {@link DocumentLinkRewriter#stampProject}).
 *
 * <p>Зачем вообще трогать сохранённый текст. {@code /files?path=…} без проекта читается как «первый
 * проект списка» — и пока проект один, это верно. Но смысл такой ссылки задан не ею самой, а
 * конфигурацией на момент открытия: стоит поставить в списке первым другой репозиторий, и каждая
 * ссылка в истории чатов и в документах начнёт открывать файл, который всего лишь совпал путём.
 * Ошибка при этом выглядит как нормальный ответ. Поэтому проект, который имелся в виду, вписывается
 * в ссылку сейчас, пока «который имелся в виду» ещё известен однозначно.
 *
 * <p>Правится только текстовая колонка и ничего больше: ни {@code updated_at}, ни {@code
 * description_version} — содержимое не изменилось, изменилась его запись, и документ,
 * «отредактированный» бэкафиллом, соврал бы истории версий. По той же причине здесь голый SQL, а не
 * репозитории: аудит Spring Data проставил бы время сам.
 *
 * <p>{@code document_history} переписывается наравне с живыми строками. Снимок версии — это то, что
 * покажут при просмотре истории, и ссылка в нём должна вести туда же, куда вела, когда снимок
 * делали.
 */
@Slf4j
@Service
public class FileLinkProjectBackfillService {

    public static final String KEY = "file-link-project-backfill";

    /** Таблица → колонки с markdown-текстом, в котором модель могла оставить ссылку на файл. */
    private static final Map<String, List<String>> TEXT_COLUMNS =
            Map.of(
                    "documents", List.of("description", "summary"),
                    "document_history", List.of("description", "summary"),
                    "chat_message", List.of("content"));

    /** Сколько строк правим одним батчем — чтобы большая база не собиралась в память целиком. */
    private static final int BATCH = 500;

    private final JdbcTemplate jdbc;
    private final BackfillStateRepository backfillStateRepository;
    private final ProjectCatalog projectCatalog;

    public FileLinkProjectBackfillService(
            JdbcTemplate jdbc,
            BackfillStateRepository backfillStateRepository,
            ProjectCatalog projectCatalog) {
        this.jdbc = jdbc;
        this.backfillStateRepository = backfillStateRepository;
        this.projectCatalog = projectCatalog;
    }

    /**
     * Точка входа со стороны {@link FileLinkProjectBackfillRunner}: маркер в {@code backfill_state}
     * ставится в той же транзакции, поэтому повторные старты — дешёвый no-op.
     */
    @Transactional
    public int stampProjectInStoredLinksIfNeeded() {
        if (backfillStateRepository.existsById(KEY)) {
            return 0;
        }
        int updated = stampProjectInStoredLinks(projectCatalog.defaultProject().id());
        backfillStateRepository.save(new BackfillStateEntity(KEY, LocalDateTime.now(), true));
        return updated;
    }

    /**
     * Сам проход. Идемпотентен: ссылка, уже несущая проект, не подходит под шаблон, а строка, в
     * которой ничего не изменилось, не переписывается.
     *
     * @return число обновлённых строк-колонок
     */
    @Transactional
    public int stampProjectInStoredLinks(String projectId) {
        int updated = 0;
        for (Map.Entry<String, List<String>> table : TEXT_COLUMNS.entrySet()) {
            for (String column : table.getValue()) {
                updated += stampColumn(table.getKey(), column, projectId);
            }
        }
        return updated;
    }

    private int stampColumn(String table, String column, String projectId) {
        // id + текст только тех строк, где ссылка вообще есть: LIKE отсекает подавляющее
        // большинство, а разбор regex-ом остаётся кандидатам.
        List<Row> candidates =
                jdbc.query(
                        "SELECT id, "
                                + column
                                + " FROM "
                                + table
                                + " WHERE "
                                + column
                                + " LIKE '%/files?path=%'",
                        (rs, i) -> new Row(rs.getLong("id"), rs.getString(column)));

        List<Object[]> batch = new ArrayList<>();
        int updated = 0;
        for (Row row : candidates) {
            String stamped = row.text() == null ? null : stamp(row.text(), projectId);
            if (stamped == null) {
                continue;
            }
            batch.add(new Object[] {stamped, row.id()});
            if (batch.size() >= BATCH) {
                updated += flush(table, column, batch);
            }
        }
        updated += flush(table, column, batch);
        if (updated > 0) {
            log.info("File-link project backfill: {}.{} — {} row(s)", table, column, updated);
        }
        return updated;
    }

    private static @Nullable String stamp(String text, String projectId) {
        return DocumentLinkRewriter.stampProject(text, projectId);
    }

    private int flush(String table, String column, List<Object[]> batch) {
        if (batch.isEmpty()) {
            return 0;
        }
        jdbc.batchUpdate("UPDATE " + table + " SET " + column + " = ? WHERE id = ?", batch);
        int size = batch.size();
        batch.clear();
        return size;
    }

    private record Row(long id, @Nullable String text) {}
}
