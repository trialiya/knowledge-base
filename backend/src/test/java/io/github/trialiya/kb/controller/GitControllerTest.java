package io.github.trialiya.kb.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.trialiya.kb.model.git.dto.GitFileNode;
import io.github.trialiya.kb.service.GitService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/**
 * Unit tests for {@code GET /api/git/tree}'s own logic — path validation and delegation to {@link
 * GitService}. Sorting and tree-building live in {@link GitService} and are covered by {@link
 * io.github.trialiya.kb.service.GitServiceTest}; re-asserting them here against a mock would just
 * duplicate that coverage.
 */
class GitControllerTest {

    private GitService gitService;
    private GitController controller;

    @BeforeEach
    void setUp() {
        gitService = mock(GitService.class);
        controller = new GitController(gitService);
    }

    @Test
    void delegatesNullPathAsRoot() {
        List<GitFileNode> expected = List.of(new GitFileNode("a", "a", "directory", null));
        when(gitService.getFileTree(null)).thenReturn(expected);

        assertThat(controller.getTree(null)).isSameAs(expected);
        verify(gitService).getFileTree(null);
    }

    @Test
    void delegatesBlankPathWithoutValidation() {
        List<GitFileNode> expected = List.of();
        when(gitService.getFileTree("  ")).thenReturn(expected);

        assertThat(controller.getTree("  ")).isSameAs(expected);
        verify(gitService).getFileTree("  ");
    }

    @Test
    void rejectsParentDirectoryTraversal() {
        assertThatThrownBy(() -> controller.getTree("../etc"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400");
    }

    @Test
    void rejectsLeadingDash() {
        assertThatThrownBy(() -> controller.getTree("-not-an-option"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400");
    }

    @Test
    void rejectsLeadingSlash() {
        assertThatThrownBy(() -> controller.getTree("/etc/passwd"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400");
    }
}
