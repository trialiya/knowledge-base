package io.github.trialiya.kb.config;

import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jdbc.repository.config.AbstractJdbcConfiguration;

/**
 * Registers the {@code float[]} ↔ {@code vector} converters so that Spring Data JDBC can read/write
 * pgvector columns transparently.
 */
@Configuration
public class PgVectorJdbcConfig extends AbstractJdbcConfiguration {

    @Override
    public List<?> userConverters() {
        return List.of(
                new FloatArrayToVectorConverter.Writer(), new FloatArrayToVectorConverter.Reader());
    }
}
