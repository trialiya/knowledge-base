package io.github.trialiya.kb.service.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import io.github.trialiya.kb.model.doc.dto.DocumentSearchGroups;
import io.github.trialiya.kb.model.doc.dto.SearchResult;
import io.github.trialiya.kb.repository.DocumentRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Страница поиска по документам: порядок остаётся за ранжированием {@link DocumentService}, а под
 * каждым документом — строки его тела с запросом, либо сниппет, когда строк нет.
 */
class DocumentSearchGroupServiceTest {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 1, 2, 3, 4);

    private DocumentService documents;
    private DocumentRepository repo;
    private DocumentSearchGroupService service;

    @BeforeEach
    void setUp() {
        documents = mock(DocumentService.class);
        repo = mock(DocumentRepository.class);
        service = new DocumentSearchGroupService(documents, repo);
    }

    private static SearchResult ranked(long id, String title, String snippet) {
        return new SearchResult(
                id, title, snippet, AT, null, List.of(new SearchResult.Parent(1, "Корень")));
    }

    @Test
    void everyMatchingLineOfTheBodyBecomesAFragmentWithItsSection() {
        when(documents.search("docker")).thenReturn(List.of(ranked(7, "Гайд", "…")));
        when(repo.findDescriptionById(7))
                .thenReturn(Optional.of("# Гайд\nпро Docker\n## Установка\nставим docker\n"));

        DocumentSearchGroups groups = service.search("docker", "keyword");

        assertThat(groups.total()).isEqualTo(2);
        DocumentSearchGroups.Group group = groups.documents().getFirst();
        assertThat(group.id()).isEqualTo(7);
        assertThat(group.parentList())
                .extracting(SearchResult.Parent::title)
                .containsExactly("Корень");
        assertThat(group.fragments())
                .extracting(
                        DocumentSearchGroups.Fragment::line, DocumentSearchGroups.Fragment::text)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(2, "про Docker"),
                        org.assertj.core.groups.Tuple.tuple(4, "ставим docker"));
        assertThat(group.fragments().getLast().sectionPath()).contains("Установка");
    }

    /** Документ, найденный по названию или по смыслу: строк с запросом нет, остаётся сниппет. */
    @Test
    void aDocumentWithoutAMatchingLineKeepsTheRankedSnippet() {
        when(documents.semanticSearch("контейнеры", null, null))
                .thenReturn(List.of(ranked(3, "Docker", "Гайд по контейнеризации")));
        when(repo.findDescriptionById(3)).thenReturn(Optional.of("Ни слова о запросе."));

        DocumentSearchGroups groups = service.search("контейнеры", "semantic");

        assertThat(groups.total()).isEqualTo(1);
        assertThat(groups.documents().getFirst().fragments())
                .containsExactly(
                        new DocumentSearchGroups.Fragment(null, null, "Гайд по контейнеризации"));
    }

    @Test
    void theQueryIsALiteralNotARegex() {
        when(documents.search("a(b")).thenReturn(List.of(ranked(1, "T", "…")));
        when(repo.findDescriptionById(1)).thenReturn(Optional.of("x a(b y\n"));

        assertThat(service.search("a(b", "keyword").total()).isEqualTo(1);
    }

    @Test
    void fragmentsPerDocumentAreCapped() {
        String body =
                IntStream.range(0, DocumentSearchGroupService.FRAGMENTS_PER_DOCUMENT + 5)
                        .mapToObj(i -> "needle " + i)
                        .collect(Collectors.joining("\n"));
        when(documents.hybridSearch("needle", null, null, null, null))
                .thenReturn(List.of(ranked(1, "T", "…")));
        when(repo.findDescriptionById(1)).thenReturn(Optional.of(body));

        DocumentSearchGroups groups = service.search("needle", "hybrid");

        assertThat(groups.documents().getFirst().fragments())
                .hasSize(DocumentSearchGroupService.FRAGMENTS_PER_DOCUMENT);
    }

    @Test
    void anUnknownModeFallsBackToKeyword() {
        when(documents.search(anyString())).thenReturn(List.of());

        assertThat(service.search("q", "whatever"))
                .isEqualTo(new DocumentSearchGroups(0, List.of()));
        verify(documents).search("q");
        verifyNoMoreInteractions(documents);
    }
}
