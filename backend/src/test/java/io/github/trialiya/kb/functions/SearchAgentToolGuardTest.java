package io.github.trialiya.kb.functions;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;

/**
 * Guards the search sub-agent's tool set. The sub-agent must be strictly read-only and must never
 * be able to call itself — both invariants are enforced by the {@code
 * kb.search.subagent.allowed-tools} allow-list applied in {@code ChatConfig#searchAgentService}.
 * This test pins that allow-list against the actual {@code @Tool} methods so a typo or an
 * accidentally-allowed write tool fails the build.
 */
class SearchAgentToolGuardTest {

    /** Mirror of {@code kb.search.subagent.allowed-tools} in application.yaml. */
    private static final Set<String> ALLOWED =
            Set.of(
                    "grepContent",
                    "searchFiles",
                    "getFileTree",
                    "getFileOutline",
                    "getFileContent",
                    "searchDocuments",
                    "findDocumentsByName",
                    "getDocument",
                    "getDocumentOutline",
                    "getDocumentSection",
                    "getTreeSkeleton");

    // Tools that mutate state or are otherwise off-limits for a read-only search agent.
    private static final Set<String> FORBIDDEN =
            Set.of(
                    "createDocument",
                    "updateDocument",
                    "updateDocumentSection",
                    "copyAttachmentToDocument",
                    "searchCodebase");

    private static Set<String> filteredToolNames() {
        // Services are never invoked by ToolCallbacks.from (it only reflects over @Tool methods),
        // so null dependencies are safe here.
        return Stream.of(
                        ToolCallbacks.from(new GitFunction(null), new DocumentFunction(null, null)))
                .map(cb -> cb.getToolDefinition().name())
                .filter(ALLOWED::contains)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    @Test
    void allowListContainsOnlyExistingReadOnlyTools() {
        // Every allow-listed name resolves to a real @Tool method (no typos / drift).
        assertThat(filteredToolNames()).containsExactlyInAnyOrderElementsOf(ALLOWED);
    }

    @Test
    void subAgentNeverSeesWriteOrSelfTools() {
        Set<String> resolved = filteredToolNames();
        assertThat(resolved).doesNotContainAnyElementsOf(FORBIDDEN);
        assertThat(ALLOWED).doesNotContain("searchCodebase");
    }

    @Test
    void searchCodebaseToolExistsButIsNotInTheAllowList() {
        Set<String> mainAgentTools =
                Arrays.stream(ToolCallbacks.from(new SearchAgentFunction(null)))
                        .map(cb -> cb.getToolDefinition().name())
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
        assertThat(mainAgentTools).contains("searchCodebase");
        assertThat(ALLOWED).doesNotContainAnyElementsOf(mainAgentTools);
    }
}
