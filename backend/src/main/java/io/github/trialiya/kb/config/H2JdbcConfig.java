package io.github.trialiya.kb.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.trialiya.kb.convert.AttachmentOwnerTypeJdbcConverter;
import io.github.trialiya.kb.convert.ChatMessageMetaToJsonConverter;
import io.github.trialiya.kb.convert.DocumentTypeJdbcConverter;
import io.github.trialiya.kb.convert.ToolDataToJsonConverter;
import io.github.trialiya.kb.convert.ToolInvocationStatusJdbcConverter;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.jdbc.core.dialect.JdbcDialect;
import org.springframework.data.jdbc.core.dialect.JdbcPostgresDialect;
import org.springframework.data.jdbc.repository.config.AbstractJdbcConfiguration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;

@Profile("h2")
@Configuration
public class H2JdbcConfig extends AbstractJdbcConfiguration {

    private final ObjectMapper objectMapper;

    public H2JdbcConfig(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public JdbcDialect jdbcDialect(NamedParameterJdbcOperations operations) {
        return JdbcPostgresDialect.INSTANCE;
    }

    @Override
    public List<?> userConverters() {
        return List.of(
                new ChatMessageMetaToJsonConverter.Writer(objectMapper),
                new ChatMessageMetaToJsonConverter.Reader(objectMapper),
                new ToolDataToJsonConverter.Writer(objectMapper),
                new ToolDataToJsonConverter.Reader(objectMapper),
                new DocumentTypeJdbcConverter.Writer(),
                new DocumentTypeJdbcConverter.Reader(),
                new AttachmentOwnerTypeJdbcConverter.Writer(),
                new AttachmentOwnerTypeJdbcConverter.Reader(),
                new ToolInvocationStatusJdbcConverter.Writer(),
                new ToolInvocationStatusJdbcConverter.Reader());
    }
}
