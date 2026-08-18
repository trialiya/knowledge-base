package io.github.trialiya.kb.config.model;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binding for {@code kb.git.*} in {@code application.yaml} — the deployment-wide defaults behind
 * {@code kb.projects} (see {@code ProjectProperties}, {@code ProjectCatalog}).
 *
 * <pre>
 * kb:
 *   git:
 *     project-path: /path/to/repo   # legacy single-project form
 *     edit-enabled: false           # default for every project
 * </pre>
 *
 * @param projectPath the repository, for a deployment that configures no {@code kb.projects} at
 *     all: it then becomes the single project, id {@code default}. Ignored once {@code kb.projects}
 *     names one, and nothing but {@code ProjectCatalog} reads it — a service that wants "the
 *     repository" asks {@code GitRegistry} for a project instead
 * @param editEnabled opt-in for the working-tree edit tools, applied to every project that does not
 *     set {@code edit-enabled} of its own. Note this is the <em>configured</em> flag: writes are
 *     still withheld when the tree is not writable, so "may the model edit this project" is
 *     answered by {@code GitRegistry#editsAllowed}, not by this value.
 */
@ConfigurationProperties(prefix = "kb.git")
public record GitProperties(@Nullable String projectPath, boolean editEnabled) {}
