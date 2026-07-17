package io.github.trialiya.kb.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.trialiya.kb.convert.AttachmentOwnerTypeJdbcConverter;
import io.github.trialiya.kb.convert.ChatMessageMetaToJsonConverter;
import io.github.trialiya.kb.convert.DocumentTypeJdbcConverter;
import io.github.trialiya.kb.convert.FloatArrayToVectorConverter;
import io.github.trialiya.kb.convert.ToolDataToJsonConverter;
import io.github.trialiya.kb.convert.ToolInvocationStatusJdbcConverter;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jdbc.repository.config.AbstractJdbcConfiguration;

/**
 * Registers the {@code float[]} ↔ {@code vector} converters so that Spring Data JDBC can read/write
 * pgvector columns transparently.
 */
@Configuration
public class PgVectorJdbcConfig extends AbstractJdbcConfiguration {

    private final ObjectMapper objectMapper;

    public PgVectorJdbcConfig(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public List<?> userConverters() {
        return List.of(
                new FloatArrayToVectorConverter.Writer(),
                new FloatArrayToVectorConverter.Reader(),
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
