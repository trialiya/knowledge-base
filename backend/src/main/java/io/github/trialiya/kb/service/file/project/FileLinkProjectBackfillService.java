package io.github.trialiya.kb.service.file.project;

import io.github.trialiya.kb.model.backfill.BackfillStateEntity;
import io.github.trialiya.kb.repository.BackfillStateRepository;
import io.github.trialiya.kb.service.document.DocumentLinkRewriter;
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

    /** Размер страницы: столько строк живёт в памяти за раз, независимо от размера базы. */
    private static final int PAGE = 500;

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

    /**
     * Страницами по {@link #PAGE}, курсором по {@code id}: сколько бы истории ни накопил инстанс, в
     * памяти лежит одна страница. Переписанные строки под {@code LIKE} по-прежнему подходят —
     * поэтому курсор идёт строго вперёд по id, а не «пока находятся кандидаты».
     */
    private int stampColumn(String table, String column, String projectId) {
        // LIKE отсекает подавляющее большинство строк, разбор regex-ом достаётся кандидатам.
        String select =
                "SELECT id, %s FROM %s WHERE %s LIKE '%%/files?path=%%' AND id > ? ORDER BY id LIMIT %d"
                        .formatted(column, table, column, PAGE);
        // Условие по прочитанному тексту склеивает чтение и запись в одну операцию. Бэкафилл
        // стартует, когда приложение уже отвечает на запросы, а колонки здесь живые: между SELECT
        // и UPDATE ту же строку может переписать правка документа или дописанный ответ прогона.
        // Без условия запись вернула бы строку к прочитанному тексту, то есть молча потеряла бы
        // чужую правку. Пропустить такую строку безопасно: ссылки, которые пишутся уже сейчас,
        // несут проект сами.
        String update =
                "UPDATE %s SET %s = ? WHERE id = ? AND %s = ?".formatted(table, column, column);

        long cursor = 0;
        int updated = 0;
        while (true) {
            List<Row> page =
                    jdbc.query(
                            select,
                            (rs, i) -> new Row(rs.getLong("id"), rs.getString(column)),
                            cursor);
            if (page.isEmpty()) {
                break;
            }
            List<Object[]> batch = new ArrayList<>();
            for (Row row : page) {
                String stamped =
                        row.text() == null
                                ? null
                                : DocumentLinkRewriter.stampProject(row.text(), projectId);
                if (stamped != null) {
                    batch.add(new Object[] {stamped, row.id(), row.text()});
                }
            }
            if (!batch.isEmpty()) {
                for (int affected : jdbc.batchUpdate(update, batch)) {
                    // Ноль — строку успели переписать между SELECT и UPDATE, она осталась чужой.
                    updated += affected > 0 ? 1 : 0;
                }
            }
            cursor = page.getLast().id();
        }
        if (updated > 0) {
            log.info("File-link project backfill: {}.{} — {} row(s)", table, column, updated);
        }
        return updated;
    }

    private record Row(long id, @Nullable String text) {}
}
