package io.github.trialiya.kb.config.model;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binding for {@code kb.git.*} in {@code application.yaml} — the legacy single-project form behind
 * {@code kb.projects} (see {@code ProjectProperties}, {@code ProjectCatalog}).
 *
 * <pre>
 * kb:
 *   git:
 *     project-path: /path/to/repo   # legacy single-project form
 * </pre>
 *
 * @param projectPath the repository, for a deployment that configures no {@code kb.projects} at
 *     all: it then becomes the single project, id {@code default}, read-only — edits are a
 *     per-project opt-in ({@code kb.projects[].edit-enabled}) and the legacy form carries none.
 *     Ignored once {@code kb.projects} names one, and nothing but {@code ProjectCatalog} reads it —
 *     a service that wants "the repository" asks {@code GitRegistry} for a project instead
 */
@ConfigurationProperties(prefix = "kb.git")
public record GitProperties(@Nullable String projectPath) {}
