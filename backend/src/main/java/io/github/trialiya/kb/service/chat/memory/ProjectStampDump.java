package io.github.trialiya.kb.service.chat.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.trialiya.kb.convert.ChatMessageMetaToJsonConverter;
import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import io.github.trialiya.kb.model.chat.entity.ChatMessageMeta;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Снимок {@code chat_message} в том виде, в каком {@link ProjectStampBackfill} его застал, — до
 * того, как он что-либо перепишет.
 *
 * <p>Проход переписывает {@code meta} чужих сообщений, и переписывает его вычислением: спаны
 * считаются из данных, а не берутся откуда-то, где они уже лежат правильными. Ошибись это
 * вычисление на форме, которой нет ни в одной фикстуре, — и вернуть исходное состояние будет
 * неоткуда: прежнего значения колонки после {@code UPDATE} не существует. Снимок и есть это
 * «неоткуда» закрытое: строчки файла — ровно те значения, что стояли в колонке до прохода.
 *
 * <p>Второе применение — то, ради которого формат читаемый: настоящие формы {@code meta} из живой
 * базы становятся фикстурой. Проход можно переиграть на них в тесте, не выдумывая, как выглядела
 * история, записанная версиями, которых уже нет.
 *
 * <p>Формат — NDJSON: по объекту на строку, файл растёт по мере обхода и не собирается в памяти
 * целиком. Полей ровно столько, сколько проход читает и пишет; {@code content} и {@code tool_data}
 * в снимок не идут — проход их не трогает, а текст переписки на диске за пределами БД никому не
 * нужен. {@code meta} лежит строкой, а не вложенным объектом: это значение колонки как есть, и
 * восстановление из него — подстановка без разбора.
 */
final class ProjectStampDump implements Closeable {

    /** UTC и без двоеточий: имя файла должно быть именем файла на любой из систем. */
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    /**
     * Строка снимка. {@code id} — адрес для восстановления, остальное — то, из чего проход считает
     * свой ответ, чтобы его можно было пересчитать и сверить, не имея доступа к самой базе.
     */
    private record DumpRow(
            long id,
            String conversationId,
            long position,
            String type,
            boolean summary,
            boolean summarized,
            @Nullable String meta) {}

    private final Path path;
    private final BufferedWriter writer;
    private final ObjectMapper objectMapper;
    private final ChatMessageMetaToJsonConverter.Writer metaWriter;
    private long rows;

    private ProjectStampDump(Path path, BufferedWriter writer, ObjectMapper objectMapper) {
        this.path = path;
        this.writer = writer;
        this.objectMapper = objectMapper;
        this.metaWriter = new ChatMessageMetaToJsonConverter.Writer(objectMapper);
    }

    /**
     * Создаёт файл снимка в каталоге {@code dir}. {@code CREATE_NEW} и порядковый номер при
     * совпадении имён: секунды в имени хватает, чтобы снимки различались глазом, но не хватает,
     * чтобы гарантировать уникальность, — а дописать чужой снимок в середину хуже, чем завести
     * второй файл.
     */
    static ProjectStampDump open(Path dir, ObjectMapper objectMapper) throws IOException {
        Files.createDirectories(dir);
        final String base = "chat-message-before-project-spans-" + STAMP.format(Instant.now());
        for (int attempt = 1; ; attempt++) {
            final Path path = dir.resolve(base + (attempt == 1 ? "" : "-" + attempt) + ".ndjson");
            try {
                return new ProjectStampDump(
                        path,
                        Files.newBufferedWriter(
                                path,
                                StandardCharsets.UTF_8,
                                StandardOpenOption.CREATE_NEW,
                                StandardOpenOption.WRITE),
                        objectMapper);
            } catch (FileAlreadyExistsException e) {
                if (attempt > 100) {
                    throw e;
                }
            }
        }
    }

    /**
     * Дописывает ряды одного чата и сбрасывает их на диск. Сброс здесь, а не при закрытии: снимок
     * обязан лежать на диске раньше, чем проход перепишет чат, из которого он снят, — иначе смысл
     * снимка теряется ровно в том случае, ради которого он и делается (проход упал посередине).
     */
    void write(List<ChatMessageEntity> rows) {
        try {
            for (ChatMessageEntity row : rows) {
                writer.write(objectMapper.writeValueAsString(dumpRow(row)));
                writer.newLine();
            }
            writer.flush();
        } catch (IOException e) {
            // Не сорвавшийся чат, а сорвавшийся снимок: дальше идти нельзя вообще, и отличить это
            // от неудачи одного чата вызывающий должен по типу (см. ProjectStampBackfill).
            throw new UncheckedIOException(e);
        }
        this.rows += rows.size();
    }

    private DumpRow dumpRow(ChatMessageEntity row) {
        final @Nullable ChatMessageMeta meta = row.getMeta();
        return new DumpRow(
                row.getId(),
                row.getConversationId(),
                row.getPosition(),
                row.getType().name(),
                row.isSummary(),
                row.isSummarized(),
                meta == null ? null : metaWriter.convert(meta));
    }

    Path path() {
        return path;
    }

    long rows() {
        return rows;
    }

    @Override
    public void close() throws IOException {
        writer.close();
    }
}
