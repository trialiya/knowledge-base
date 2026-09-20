package io.github.trialiya.kb.tools;

import io.modelcontextprotocol.client.McpSyncClient;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.McpToolFilter;
import org.springframework.ai.mcp.McpToolNamePrefixGenerator;
import org.springframework.ai.mcp.McpToolsChangedEvent;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.mcp.ToolContextToMcpMetaConverter;
import org.springframework.ai.mcp.client.common.autoconfigure.properties.McpClientCommonProperties;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * What the configured MCP servers advertise right now, held per connection and re-read off the
 * servers in the background — never on the way up.
 *
 * <p>The startup order this exists for: {@code spring.ai.mcp.client.initialized=false} keeps the
 * autoconfiguration from opening the connections while beans are being created, and nothing here
 * touches a server until {@link ApplicationReadyEvent}. A server that is down — or a URL that
 * resolves to nothing, or an {@code npx} that is not installed — therefore costs its own tools and
 * nothing else: the application starts, the other connections keep theirs, and the chat runs with
 * the built-in tools.
 *
 * <p>A connection is probed again on {@code tools/list_changed} and, while it is not {@link
 * Status#UP}, every {@code kb.mcp.retry-interval-ms} — so a server that comes up an hour after the
 * application does is picked up without a restart. That is also why the model's tool list is
 * assembled per request ({@code ChatRunService}) rather than baked into the {@code ChatClient}: see
 * {@link ChatToolset}.
 *
 * <p>Tools of a connection that has just failed are dropped rather than kept as a last known good
 * list: a call to a server that is down buys an error message the model has to spend a round trip
 * reading, and the tool comes back on the next successful probe anyway.
 *
 * <p>Sync clients only ({@code spring.ai.mcp.client.type=SYNC}, the default this project pins in
 * {@code application.yaml}). Under {@code ASYNC} the autoconfiguration registers {@code
 * McpAsyncClient}s instead, there is nothing here to probe, and the model is offered no MCP tools
 * at all.
 */
@Slf4j
public class McpToolRegistry {

    /**
     * One connection's tool list, re-read from its server on every call — the unit this class
     * retries, logs and reports on. An interface rather than the provider itself so the failure
     * modes can be tested without a server on the other end.
     */
    @FunctionalInterface
    public interface ToolSource {
        List<ToolCallback> list();
    }

    /**
     * {@code PENDING} — not probed yet (the window between context start and the first background
     * probe); {@code UP} — the tool list below came off the server; {@code DOWN} — the last probe
     * failed, and the connection is being retried.
     */
    public enum Status {
        PENDING,
        UP,
        DOWN
    }

    /** One connection as the Settings panel reports it. */
    public record ConnectionStatus(String name, Status status, int toolCount) {}

    private record Connection(Status status, List<ToolCallback> callbacks) {}

    /**
     * The callbacks and the per-connection report, swapped as one: a reader of {@link #callbacks()}
     * building a request must not see a tool list that half a refresh has already replaced.
     */
    private record Snapshot(List<ToolCallback> callbacks, List<ConnectionStatus> statuses) {}

    private final Map<String, ToolSource> sources;
    private final Map<String, Connection> connections = new ConcurrentHashMap<>();

    /**
     * Held for the duration of a probe round. {@code tryLock} rather than {@code lock}: a refresh
     * that arrives while one is running is dropped, not queued — the running one is already asking
     * the same servers the same question.
     */
    private final Lock refreshing = new ReentrantLock();

    /** Connections waiting to be probed, drained by whoever holds {@link #refreshing}. */
    private final Set<String> queued = ConcurrentHashMap.newKeySet();

    private volatile Snapshot snapshot = new Snapshot(List.of(), List.of());

    /**
     * Tool name prefixes, the tool filter and the tool-context converter are taken from the beans
     * the MCP autoconfiguration contributes, because the provider built here replaces the one it
     * would have built: without them a tool would reach the model under a different name than the
     * starter gives it.
     */
    public McpToolRegistry(
            ObjectProvider<List<McpSyncClient>> mcpClients,
            ObjectProvider<McpClientCommonProperties> commonProperties,
            ObjectProvider<McpToolFilter> toolFilter,
            ObjectProvider<McpToolNamePrefixGenerator> prefixGenerator,
            ObjectProvider<ToolContextToMcpMetaConverter> metaConverter) {
        this(
                sources(
                        mcpClients.getIfAvailable(List::of),
                        commonProperties,
                        toolFilter,
                        prefixGenerator,
                        metaConverter));
    }

    McpToolRegistry(Map<String, ToolSource> sources) {
        this.sources = sources;
        sources.keySet().forEach(name -> connections.put(name, pending()));
        this.snapshot = snapshot();
        if (sources.isEmpty()) {
            log.info("MCP tools enabled, but no connections are configured");
        } else {
            log.info("MCP connections to probe after startup: {}", sources.keySet());
        }
    }

    /** The MCP tools the model may be offered right now, already wrapped for run recording. */
    public List<ToolCallback> callbacks() {
        return snapshot.callbacks();
    }

    /** Every configured connection with the state of its last probe, for the Settings panel. */
    public List<ConnectionStatus> statuses() {
        return snapshot.statuses();
    }

    /**
     * The first probe of every connection. On a virtual thread and after the context is up, so a
     * server that answers slowly — or not at all — delays its own tools and not the port.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void connect() {
        inBackground(() -> refresh(sources.keySet()));
    }

    /**
     * Re-probes every connection, the healthy ones included. A connection that is down is the
     * obvious case — that is how a server started after the application is picked up — but an UP
     * one is asked as well, because nothing else would ever notice that it died: a server that
     * stops answering sends no notification, and leaving it UP means offering the model tools that
     * can only fail, under a Settings panel calling it connected.
     */
    // The initial delay is what keeps the first probe where connect() puts it: a fixed delay with
    // no initial one starts counting at lifecycle start, which is before ApplicationReadyEvent —
    // i.e. it would reach out to the servers during the very startup this class exists to keep
    // clear of them.
    @Scheduled(
            fixedDelayString = "${kb.mcp.retry-interval-ms:60000}",
            initialDelayString = "${kb.mcp.retry-interval-ms:60000}")
    public void refreshAll() {
        if (!sources.isEmpty()) {
            inBackground(() -> refresh(sources.keySet()));
        }
    }

    /**
     * A server telling us its tool list changed ({@code notifications/tools/list_changed}). The
     * re-read is moved off this thread on purpose: the notification is delivered on the client's
     * own transport thread, which is the thread the {@code tools/list} reply would have to arrive
     * on.
     */
    @EventListener
    public void onToolsChanged(McpToolsChangedEvent event) {
        String connection = event.getConnectionName();
        if (sources.containsKey(connection)) {
            log.info("MCP connection '{}' announced a changed tool list", connection);
            inBackground(() -> refresh(List.of(connection)));
        }
    }

    /**
     * Probes the named connections, one round at a time. Names are queued first and the lock only
     * tried after: a caller that finds a round already running has already handed its work to it,
     * so nothing is dropped — which matters most for the one refresh that no schedule repeats, a
     * {@code tools/list_changed} landing mid-round. The loop runs again for whatever was queued
     * while it was draining.
     */
    private void refresh(Collection<String> names) {
        queued.addAll(names);
        while (!queued.isEmpty() && refreshing.tryLock()) {
            try {
                drain();
            } finally {
                refreshing.unlock();
            }
        }
    }

    private void drain() {
        for (Iterator<String> names = queued.iterator(); names.hasNext(); ) {
            String name = names.next();
            names.remove();
            connections.put(name, probe(name));
        }
        snapshot = snapshot();
    }

    private Connection probe(String name) {
        ToolSource source = sources.get(name);
        if (source == null) {
            return pending();
        }
        Connection previous = connections.get(name);
        try {
            List<ToolCallback> callbacks =
                    source.list().stream().<ToolCallback>map(RecordingToolCallback::new).toList();
            if (previous == null || previous.status() != Status.UP) {
                log.info("MCP connection '{}' is up: {} tool(s)", name, callbacks.size());
            }
            return new Connection(Status.UP, callbacks);
        } catch (Exception e) {
            // Only the change of state is a WARN, and only its message, not the stack trace: a
            // server that stays down is retried every kb.mcp.retry-interval-ms, and one line per
            // interval per connection would bury the log of a deployment nobody is going to fix
            // today. The Settings panel reports the state either way.
            if (previous == null || previous.status() != Status.DOWN) {
                log.warn(
                        "MCP connection '{}' is unavailable, its tools are not offered to the"
                                + " model: {}",
                        name,
                        e.toString());
            } else {
                log.debug("MCP connection '{}' is still unavailable", name, e);
            }
            return new Connection(Status.DOWN, List.of());
        }
    }

    /** Rebuilds the published view out of {@link #connections}, in configuration order. */
    private Snapshot snapshot() {
        List<ToolCallback> callbacks = new ArrayList<>();
        List<ConnectionStatus> statuses = new ArrayList<>();
        sources.keySet()
                .forEach(
                        name -> {
                            Connection connection = connections.getOrDefault(name, pending());
                            callbacks.addAll(connection.callbacks());
                            statuses.add(
                                    new ConnectionStatus(
                                            name,
                                            connection.status(),
                                            connection.callbacks().size()));
                        });
        return new Snapshot(List.copyOf(callbacks), List.copyOf(statuses));
    }

    private static Connection pending() {
        return new Connection(Status.PENDING, List.of());
    }

    private static void inBackground(Runnable task) {
        Thread.ofVirtual().name("mcp-refresh").start(task);
    }

    // The clients are beans: the autoconfiguration hands them out already open and closes them
    // with the context (CloseableMcpSyncClients). Closing one here would take the connection
    // away from everything else that holds it.
    @SuppressWarnings("PMD.CloseResource")
    private static Map<String, ToolSource> sources(
            List<McpSyncClient> clients,
            ObjectProvider<McpClientCommonProperties> commonProperties,
            ObjectProvider<McpToolFilter> toolFilter,
            ObjectProvider<McpToolNamePrefixGenerator> prefixGenerator,
            ObjectProvider<ToolContextToMcpMetaConverter> metaConverter) {
        String clientName =
                commonProperties.getIfAvailable(McpClientCommonProperties::new).getName();
        Map<String, ToolSource> sources = new LinkedHashMap<>();
        for (McpSyncClient client : clients) {
            SyncMcpToolCallbackProvider.Builder builder =
                    SyncMcpToolCallbackProvider.builder().mcpClients(client);
            toolFilter.ifAvailable(builder::toolFilter);
            prefixGenerator.ifAvailable(builder::toolNamePrefixGenerator);
            metaConverter.ifAvailable(builder::toolContextToMcpMetaConverter);
            SyncMcpToolCallbackProvider provider = builder.build();
            ToolSource source =
                    () -> {
                        // The provider caches the tool list of a connection it has already read;
                        // a probe exists to find out whether that list still holds.
                        provider.invalidateCache();
                        return List.of(provider.getToolCallbacks());
                    };
            String name = connectionName(clientName, client);
            // Two transports may carry the same connection name — a misconfiguration, but not one
            // that should cost a server its tools: the name then stands for both, and either of
            // them failing takes the pair down.
            sources.merge(
                    name,
                    source,
                    (existing, added) -> {
                        log.warn(
                                "Two MCP connections are named '{}' — they are probed and reported"
                                        + " as one",
                                name);
                        return () ->
                                Stream.concat(existing.list().stream(), added.list().stream())
                                        .toList();
                    });
        }
        return Collections.unmodifiableMap(sources);
    }

    /**
     * The connection name as {@code spring.ai.mcp.client.*.connections} spells it — the key {@code
     * kb.mcp.bearer-tokens} and the Settings panel use. A client is named {@code "<client name> -
     * <connection>"} by the autoconfiguration; a client whose name does not carry that prefix is
     * reported under the name it has, since a wrong key here would be a connection the panel
     * silently drops.
     */
    private static String connectionName(String clientName, McpSyncClient client) {
        String name = client.getClientInfo().name();
        String prefix = clientName + " - ";
        return name.startsWith(prefix) ? name.substring(prefix.length()) : name;
    }
}
