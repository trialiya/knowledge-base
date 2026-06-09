package io.github.trialiya.kb.config.model;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "kb.chat")
public record ChatModelProperties(ModelOption defaultModel, List<ModelOption> models) {

    public ChatModelProperties {
        models = models == null ? List.of() : List.copyOf(models);
    }

    public record ModelOption(String id, String label) {}

    public boolean isAllowed(String id) {
        return id != null
                && (id.equals(defaultModel.id())
                        || models.stream().anyMatch(m -> id.equals(m.id())));
    }
}
