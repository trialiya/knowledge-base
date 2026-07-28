package io.github.trialiya.kb.config.model;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binding for {@code kb.git.*} in {@code application.yaml}.
 *
 * <pre>
 * kb:
 *   git:
 *     project-path: /path/to/repo
 *     edit-enabled: false
 * </pre>
 *
 * <p>Both values were read as loose {@code @Value} placeholders from three different beans ({@code
 * GitService}, {@code SettingsController}, {@code SystemInfoController}), each repeating the key
 * and its default. One binding keeps the defaults in a single place.
 *
 * @param projectPath repository indexed and served to the chat model; required
 * @param editEnabled explicit opt-in for the working-tree edit tools. Note this is the
 *     <em>configured</em> flag: the tools are still withheld when the tree is not writable, so "are
 *     they actually exposed" is answered by the presence of the {@code GitEditFunction} bean (see
 *     {@code ChatConfig#gitEditFunction}), not by this value.
 */
@ConfigurationProperties(prefix = "kb.git")
public record GitProperties(String projectPath, boolean editEnabled) {}
