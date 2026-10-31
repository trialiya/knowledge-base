package io.github.trialiya.kb.service.chat.script;

import io.github.trialiya.kb.config.model.ScriptMcpProperties;
import io.github.trialiya.kb.config.model.ScriptProperties;
import io.github.trialiya.kb.config.model.ScriptResultProperties;
import io.github.trialiya.kb.service.document.DocumentService;
import io.github.trialiya.kb.service.file.git.GitRegistry;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.util.unit.DataSize;

/** Script services for the tests that are not about MCP: no run gets an {@code mcp} object. */
public final class ScriptFixtures {

    private ScriptFixtures() {}

    public static ScriptMcpProperties mcpDisabled() {
        return new ScriptMcpProperties(false, 20, DataSize.ofMegabytes(16));
    }

    public static ScriptMcp noMcp() {
        return new ScriptMcp(mcpDisabled(), Map.of(), () -> null);
    }

    public static ScriptRunner runner(
            GitRegistry gitRegistry,
            @Nullable DocumentService documentService,
            ScriptProperties properties,
            ScriptEditPolicy editPolicy,
            ScriptResultStore resultStore) {
        return new ScriptRunner(gitRegistry, documentService, properties, editPolicy, resultStore, noMcp());
    }

    public static ScriptGuideService guides(
            ScriptProperties properties, ScriptResultProperties results, ScriptEditPolicy editPolicy) {
        return new ScriptGuideService(properties, results, editPolicy, noMcp());
    }
}
