package io.github.trialiya.kb.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.concurrent.DelegatingSecurityContextExecutorService;

@Configuration
public class DocumentSyncConfig {

    /**
     * Pool for export / compare / import runs. These stream their progress over SSE, and returning
     * the emitter is what frees the servlet thread — so the work itself has to happen somewhere
     * else, one virtual thread per run. The wrapper carries the caller's {@link
     * org.springframework.security.core.context.SecurityContext} onto the worker, and {@code
     * destroyMethod = "shutdown"} stops the underlying executor with the context.
     */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService documentSyncExecutor() {
        return new DelegatingSecurityContextExecutorService(
                Executors.newVirtualThreadPerTaskExecutor());
    }
}
