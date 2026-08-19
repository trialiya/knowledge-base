package io.github.trialiya.kb.support;

import io.github.trialiya.kb.config.model.GitProperties;
import io.github.trialiya.kb.config.model.ProjectProperties;
import io.github.trialiya.kb.config.model.ProjectProperties.ProjectOption;
import io.github.trialiya.kb.service.GitRegistry;
import io.github.trialiya.kb.service.GitService;
import io.github.trialiya.kb.service.OutlineService;
import io.github.trialiya.kb.service.ProjectCatalog;
import java.nio.file.Path;
import java.util.List;

/**
 * One repository in a temp directory, wrapped in the same {@link ProjectCatalog}/{@link
 * GitRegistry} pair the application builds — so a test that needs a {@link GitService} gets one
 * through the production path rather than assembling a project descriptor of its own.
 */
public final class TestProjects {

    public static final String ID = "default";

    private TestProjects() {}

    /** A registry over the single repository at {@code repoDir}. */
    public static GitRegistry registry(Path repoDir, boolean editEnabled) {
        ProjectProperties properties =
                new ProjectProperties(
                        List.of(
                                new ProjectOption(
                                        ID, null, repoDir.toString(), editEnabled, true)));
        return new GitRegistry(
                new ProjectCatalog(properties, new GitProperties(null, false)),
                new OutlineService());
    }

    /** The {@link GitService} of that single repository. */
    public static GitService gitService(Path repoDir, boolean editEnabled) {
        return registry(repoDir, editEnabled).defaultProject();
    }
}
