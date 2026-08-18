package io.github.trialiya.kb;

import io.github.trialiya.kb.config.model.ChatModeProperties;
import io.github.trialiya.kb.config.model.ChatModelProperties;
import io.github.trialiya.kb.config.model.ChatTimeoutProperties;
import io.github.trialiya.kb.config.model.DocumentsConfiguration;
import io.github.trialiya.kb.config.model.EmbeddingConfiguration;
import io.github.trialiya.kb.config.model.GitProperties;
import io.github.trialiya.kb.config.model.McpProperties;
import io.github.trialiya.kb.config.model.ProjectProperties;
import io.github.trialiya.kb.config.model.ScriptProperties;
import io.github.trialiya.kb.config.model.SearchConfiguration;
import io.github.trialiya.kb.config.model.SecurityProperties;
import io.github.trialiya.kb.config.model.SubAgentConfig;
import io.github.trialiya.kb.config.model.SummarizeProperties;
import io.github.trialiya.kb.config.model.SystemPromptProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({
    DocumentsConfiguration.class,
    EmbeddingConfiguration.class,
    GitProperties.class,
    ProjectProperties.class,
    ScriptProperties.class,
    SearchConfiguration.class,
    SubAgentConfig.class,
    McpProperties.class,
    ChatModelProperties.class,
    ChatModeProperties.class,
    ChatTimeoutProperties.class,
    SecurityProperties.class,
    SummarizeProperties.class,
    SystemPromptProperties.class
})
public class KnowledgeBaseApplication {

    public static void main(String[] args) {
        SpringApplication.run(KnowledgeBaseApplication.class, args);
    }
}
