package io.github.trialiya.kb;

import io.github.trialiya.kb.config.model.AtlassianConfiguration;
import io.github.trialiya.kb.config.model.ChatModelProperties;
import io.github.trialiya.kb.config.model.DocumentsConfiguration;
import io.github.trialiya.kb.config.model.EmbeddingConfiguration;
import io.github.trialiya.kb.config.model.SearchConfiguration;
import io.github.trialiya.kb.config.model.SecurityProperties;
import io.github.trialiya.kb.config.model.SubAgentConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({
    DocumentsConfiguration.class,
    EmbeddingConfiguration.class,
    SearchConfiguration.class,
    SubAgentConfig.class,
    AtlassianConfiguration.class,
    ChatModelProperties.class,
    SecurityProperties.class
})
public class KnowledgeBaseApplication {

    public static void main(String[] args) {
        SpringApplication.run(KnowledgeBaseApplication.class, args);
    }
}
