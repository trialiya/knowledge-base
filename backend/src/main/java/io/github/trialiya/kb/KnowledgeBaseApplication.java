package io.github.trialiya.kb;

import io.github.trialiya.kb.config.model.AtlassianConfiguration;
import io.github.trialiya.kb.config.model.ChatModelProperties;
import io.github.trialiya.kb.config.model.ChatTimeoutProperties;
import io.github.trialiya.kb.config.model.DocumentsConfiguration;
import io.github.trialiya.kb.config.model.EmbeddingConfiguration;
import io.github.trialiya.kb.config.model.SearchConfiguration;
import io.github.trialiya.kb.config.model.SecurityProperties;
import io.github.trialiya.kb.config.model.SubAgentConfig;
import io.github.trialiya.kb.config.model.SummarizeProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({
    DocumentsConfiguration.class,
    EmbeddingConfiguration.class,
    SearchConfiguration.class,
    SubAgentConfig.class,
    AtlassianConfiguration.class,
    ChatModelProperties.class,
    ChatTimeoutProperties.class,
    SecurityProperties.class,
    SummarizeProperties.class
})
public class KnowledgeBaseApplication {

    public static void main(String[] args) {
        SpringApplication.run(KnowledgeBaseApplication.class, args);
    }
}
