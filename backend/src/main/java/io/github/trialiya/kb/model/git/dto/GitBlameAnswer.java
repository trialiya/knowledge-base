package io.github.trialiya.kb.model.git.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.trialiya.kb.model.tool.ToolCallResponseItem;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Ответ инструмента {@code getBlame} — тот же {@link GitFileBlame}, сложенный плотнее: коммит
 * описан один раз в словаре {@code commits}, а ханк ссылается на него хешем.
 *
 * <p>Ханков у файла с историей в разы больше, чем коммитов: десятки ханков из пяти-шести коммитов
 * — обычный диапазон, и {@link GitFileBlame} повторял бы в каждом автора, дату и тему коммита.
 * Колонка blame в «Файлах» получает {@link GitFileBlame} как есть: ей нужна строка на ханк, а не
 * экономия контекста.
 *
 * <p>По той же причине ханк опускает то, что совпадает с очевидным: {@code path} — если это путь
 * файла, {@code sourceLine} — если строка в том коммите та же, что сейчас. Режим «Обзор» модалки
 * вызова ({@code blameResult.js}) разворачивает ответ обратно в строки.
 *
 * @param path относительный путь от корня репозитория
 * @param commit полный хеш ревизии, в снимке которой смотрели файл; {@code null} — рабочее дерево
 * @param lineCount сколько строк во всём файле, а не в диапазоне
 * @param fromLine первая строка ответа; {@code null} — спрашивали весь файл
 * @param toLine последняя строка ответа (включительно); см. {@link GitFileBlame#toLine()}
 * @param commits коммиты ханков по полному хешу, в порядке первого появления
 * @param hunks диапазоны в порядке строк
 * @param gist плашка вызова — та же, что у {@link GitFileBlame}; в JSON не попадает
 */
public record GitBlameAnswer(
        String path,
        @Nullable @JsonInclude(JsonInclude.Include.NON_NULL) String commit,
        int lineCount,
        @Nullable @JsonInclude(JsonInclude.Include.NON_NULL) Integer fromLine,
        @Nullable @JsonInclude(JsonInclude.Include.NON_NULL) Integer toLine,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) Map<String, Commit> commits,
        List<Hunk> hunks,
        @JsonIgnore String gist)
        implements ToolCallResponseItem {

    /** Ответ blame в плотной форме; плашку вызова считает сам {@link GitFileBlame}. */
    public static GitBlameAnswer of(GitFileBlame blame) {
        Map<String, Commit> commits = new LinkedHashMap<>();
        List<Hunk> hunks = blame.hunks().stream()
                .map(hunk -> {
                    if (hunk.hash() != null) {
                        commits.putIfAbsent(hunk.hash(), Commit.of(hunk));
                    }
                    return Hunk.of(hunk, blame.path());
                })
                .toList();
        return new GitBlameAnswer(
                blame.path(),
                blame.commit(),
                blame.lineCount(),
                blame.fromLine(),
                blame.toLine(),
                commits,
                hunks,
                blame.getFormattedResponse());
    }

    @Override
    public String getFormattedResponse() {
        return gist;
    }

    /**
     * Коммит, к которому восходят строки ханков. Поля описания — все или ни одного (см. {@link
     * GitFileBlame.Hunk}); у коммита на границе неглубокого клона нет автора и темы.
     *
     * @param author имя автора
     * @param date дата авторства
     * @param summary первая строка сообщения коммита
     * @param boundary коммит — граница неглубокого клона: его строки могут быть старше него, и кто их
     *     написал, клон не знает
     */
    public record Commit(
            @Nullable @JsonInclude(JsonInclude.Include.NON_NULL)
            String author,

            @Nullable @JsonInclude(JsonInclude.Include.NON_NULL)
            OffsetDateTime date,

            @Nullable @JsonInclude(JsonInclude.Include.NON_NULL)
            String summary,

            @JsonInclude(JsonInclude.Include.NON_DEFAULT) boolean boundary) {

        static Commit of(GitFileBlame.Hunk hunk) {
            return new Commit(hunk.author(), hunk.date(), hunk.summary(), hunk.boundary());
        }
    }

    /**
     * Строки {@code fromLine}…{@code fromLine + lineCount - 1}, последними изменённые одним коммитом.
     *
     * @param hash полный хеш коммита — ключ в {@code commits}; {@code null} — незакоммиченная правка
     * @param path путь файла в том коммите; {@code null} — тот же, что у файла ответа
     * @param sourceLine первая строка диапазона в файле того коммита; {@code null} — та же, что
     *     {@code fromLine}, или коммита нет
     */
    public record Hunk(
            int fromLine,
            int lineCount,

            @Nullable @JsonInclude(JsonInclude.Include.NON_NULL)
            String hash,

            @Nullable @JsonInclude(JsonInclude.Include.NON_NULL)
            String path,

            @Nullable @JsonInclude(JsonInclude.Include.NON_NULL)
            Integer sourceLine) {

        static Hunk of(GitFileBlame.Hunk hunk, String file) {
            return new Hunk(
                    hunk.fromLine(),
                    hunk.lineCount(),
                    hunk.hash(),
                    Objects.equals(hunk.path(), file) ? null : hunk.path(),
                    Objects.equals(hunk.sourceLine(), hunk.fromLine()) ? null : hunk.sourceLine());
        }
    }
}
