package io.github.trialiya.kb.config.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** A connection key left blank in {@code kb.mcp.*} is "nothing of its own", not a failed start. */
class McpPropertiesTest {

    @Test
    void aBlankConnectionEntryIsNoEntry() {
        Binder binder = new Binder(new MapConfigurationPropertySource(Map.of(
                "kb.mcp.result-limits.confluence", "",
                "kb.mcp.result-limits.jira", "5000",
                "kb.mcp.script-access.wiki", "")));

        McpProperties properties = binder.bind("kb.mcp", McpProperties.class).get();

        assertThat(properties.resultLimits()).containsExactly(Map.entry("jira", 5000));
        // A blank script-access entry binds as an entry with no tools, which ScriptMcp drops.
        assertThat(properties.scriptAccess().get("wiki").tools()).isEmpty();
    }
}
