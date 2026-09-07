package io.github.trialiya.kb.service.document;

import io.github.trialiya.kb.model.doc.dto.DocumentGrepMatch;
import io.github.trialiya.kb.model.doc.dto.DocumentSearchGroups;
import io.github.trialiya.kb.model.doc.dto.SearchResult;
import io.github.trialiya.kb.repository.DocumentRepository;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * The search page's view of the knowledge base: the documents {@link DocumentService} ranks for a
 * query, each with every line of its body the query occurs in.
 *
 * <p>Ranking stays with the existing search in whichever mode was asked for — the page must agree
 * with the header search on what comes first. The lines come from {@link DocumentGrep} over the
 * body of each ranked document, read one at a time the way {@code grepDocuments} reads them: the
 * ranked list is short (the mode's configured limit), so this costs one body read per hit rather
 * than a scan of the base.
 */
@Service
public class DocumentSearchGroupService {

    /**
     * Lines shown per document. A term that occurs on every line of a long document would otherwise
     * push every other document below the fold; past this many the count is what matters.
     */
    static final int FRAGMENTS_PER_DOCUMENT = 20;

    private final DocumentService documents;
    private final DocumentRepository repo;

    public DocumentSearchGroupService(DocumentService documents, DocumentRepository repo) {
        this.documents = documents;
        this.repo = repo;
    }

    /**
     * @param mode {@code keyword}, {@code semantic} or {@code hybrid} — the same spellings {@code
     *     GET /api/documents/search} takes; anything else is keyword
     */
    public DocumentSearchGroups search(String q, String mode) {
        List<SearchResult> ranked =
                switch (mode.toLowerCase(Locale.ROOT)) {
                    case "semantic" -> documents.semanticSearch(q, null, null);
                    case "hybrid" -> documents.hybridSearch(q, null, null, null, null);
                    default -> documents.search(q);
                };
        // Literal, never a regex: the page searches for what was typed, and a query that happens to
        // contain a bracket must not turn into an error or an empty list.
        Pattern pattern = DocumentGrep.compile(q, false);
        List<DocumentSearchGroups.Group> groups =
                ranked.stream().map(result -> group(result, pattern)).toList();
        int total = groups.stream().mapToInt(g -> g.fragments().size()).sum();
        return new DocumentSearchGroups(total, groups);
    }

    private DocumentSearchGroups.Group group(SearchResult result, Pattern pattern) {
        long id = Objects.requireNonNull(result.id());
        String body = repo.findDescriptionById(id).orElse("");
        List<DocumentSearchGroups.Fragment> fragments =
                DocumentGrep.matches(id, result.title(), body, pattern, 0, FRAGMENTS_PER_DOCUMENT)
                        .stream()
                        .map(DocumentSearchGroupService::fragment)
                        .toList();
        if (fragments.isEmpty()) {
            // Found by title, summary or meaning rather than by a line of the body: the ranked
            // result's own snippet is the one thing there is to show for it.
            fragments = List.of(new DocumentSearchGroups.Fragment(null, null, result.snippet()));
        }
        return new DocumentSearchGroups.Group(
                id,
                result.title(),
                result.updatedAt(),
                result.parentList() == null ? List.of() : result.parentList(),
                fragments);
    }

    private static DocumentSearchGroups.Fragment fragment(DocumentGrepMatch match) {
        return new DocumentSearchGroups.Fragment(
                match.matchLine(), match.sectionPath(), match.text());
    }
}
