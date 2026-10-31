package io.github.trialiya.kb.service.chat.script;

/**
 * Which kind of run a script is, as far as MCP tools are concerned — the caller's to say, because
 * nothing else about a run tells it: a {@code runScript} against another project is read-only, but
 * that is about the repository, not about external servers.
 *
 * <p>The scope only opens the door; what is behind it is {@code kb.script.mcp.enabled} and {@code
 * kb.mcp.script-access} (see {@code ScriptMcp}).
 */
public enum ScriptMcpScope {
    /** No {@code mcp} object: the search sub-agent, the settings bench, an attachment's code. */
    NONE,
    /** A run in a chat, with a person behind it: the model's own, or {@code /script}. */
    CHAT,
    /** A scheduled run — only the connections whose {@code script-access} says {@code scheduled}. */
    SCHEDULED
}
