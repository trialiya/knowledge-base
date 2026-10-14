package io.github.trialiya.kb.functions;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

/**
 * Guards the search sub-agent's tool set. The sub-agent must be strictly read-only and must never
 * be able to call itself — both invariants are enforced by the {@code
 * kb.search.subagent.allowed-tools} allow-list applied in {@code ChatConfig#searchAgentService}.
 * This test reads that allow-list from application.yaml and pins it against the actual {@code
 * @Tool} methods of the sub-agent's pool, so a typo or an accidentally-allowed write tool fails the
 * build.
 */
class SearchAgentToolGuardTest {

    /** The allow-list as the application ships it — read from application.yaml, not copied. */
    private static final Set<String> ALLOWED = shippedAllowList();

    // Tools that mutate state or are otherwise off-limits for a read-only search agent.
    private static final Set<String> FORBIDDEN = Set.of(
            "createDocument",
            "updateDocument",
            "editDocument",
            "updateDocumentSection",
            "insertDocumentSection",
            "deleteDocumentSection",
            "renameDocumentSections",
            "retryDocumentWrite",
            "copyAttachmentToDocument",
            "searchCodebase");

    private static Set<String> shippedAllowList() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yaml"));
        Properties properties = Objects.requireNonNull(yaml.getObject());
        Set<String> allowed = new HashSet<>();
        for (int i = 0; properties.containsKey("kb.search.subagent.allowed-tools[" + i + "]"); i++) {
            allowed.add(properties.getProperty("kb.search.subagent.allowed-tools[" + i + "]"));
        }
        return Set.copyOf(allowed);
    }

    /**
     * The sub-agent's pool, as {@code ChatConfig#searchAgentService} builds it, narrowed to the
     * allow-list. Services are never invoked by ToolCallbacks.from (it only reflects over @Tool
     * methods), so null dependencies are safe here.
     */
    private static Set<String> filteredToolNames() {
        return Stream.of(ToolCallbacks.from(
                        new GitFunction(null),
                        new GitHistoryFunction(null),
                        new DocumentFunction(null, null, null),
                        ScriptFunction.readOnly(null, null)))
                .map(cb -> cb.getToolDefinition().name())
                .filter(ALLOWED::contains)
                .collect(Collectors.toUnmodifiableSet());
    }

    @Test
    void allowListContainsOnlyExistingReadOnlyTools() {
        // Every allow-listed name resolves to a real @Tool method (no typos / drift).
        assertThat(filteredToolNames()).containsExactlyInAnyOrderElementsOf(ALLOWED);
    }

    @Test
    void subAgentNeverSeesWriteOrSelfTools() {
        assertThat(ALLOWED).isNotEmpty().doesNotContainAnyElementsOf(FORBIDDEN);
    }

    @Test
    void searchCodebaseToolExistsButIsNotInTheAllowList() {
        Set<String> mainAgentTools = Arrays.stream(ToolCallbacks.from(new SearchAgentFunction(null)))
                .map(cb -> cb.getToolDefinition().name())
                .collect(Collectors.toUnmodifiableSet());
        assertThat(mainAgentTools).contains("searchCodebase");
        assertThat(ALLOWED).doesNotContainAnyElementsOf(mainAgentTools);
    }
}
