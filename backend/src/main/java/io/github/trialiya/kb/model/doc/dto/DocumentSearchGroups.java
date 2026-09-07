package io.github.trialiya.kb.model.doc.dto;

import java.time.LocalDateTime;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Поиск по документам для страницы поиска: документ один раз, под ним все найденные в нём
 * фрагменты. Порядок документов — как у {@code GET /api/documents/search} в том же режиме.
 *
 * @param total сколько фрагментов во всех документах вместе
 * @param documents найденные документы
 */
public record DocumentSearchGroups(int total, List<Group> documents) {

    /**
     * Один документ с его фрагментами.
     *
     * @param parentList хлебные крошки от корня до родителя, как у {@link SearchResult}
     * @param fragments вхождения запроса по порядку в тексте; документ, найденный только по
     *     названию или по смыслу, несёт один фрагмент-сниппет без номера строки
     */
    public record Group(
            long id,
            String title,
            LocalDateTime updatedAt,
            List<SearchResult.Parent> parentList,
            List<Fragment> fragments) {}

    /**
     * Одно вхождение в тексте документа.
     *
     * @param line номер строки markdown (1-based); {@code null} у сниппета, который не привязан к
     *     строке
     * @param sectionPath раздел, в который попало вхождение (как в {@code getDocumentOutline});
     *     {@code null}, когда его нет
     * @param text строка целиком
     */
    public record Fragment(@Nullable Integer line, @Nullable String sectionPath, String text) {}
}
