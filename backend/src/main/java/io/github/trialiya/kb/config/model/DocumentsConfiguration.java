package io.github.trialiya.kb.config.model;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "kb.documents")
public record DocumentsConfiguration(String exportPath, boolean replace) {}
