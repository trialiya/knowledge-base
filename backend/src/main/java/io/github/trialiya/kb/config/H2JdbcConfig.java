package io.github.trialiya.kb.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.jdbc.core.dialect.JdbcDialect;
import org.springframework.data.jdbc.core.dialect.JdbcPostgresDialect;
import org.springframework.data.jdbc.repository.config.AbstractJdbcConfiguration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;

@Profile("h2")
@Configuration
public class H2JdbcConfig extends AbstractJdbcConfiguration {

    //    @Bean
    //    public NamingStrategy namingStrategy() {
    //        return new DefaultNamingStrategy() {
    //            @Override
    //            public String getReverseColumnName(RelationalPersistentProperty property) {
    //                return super.getReverseColumnName(property);
    //            }
    //
    //            @Override
    //            public String getColumnName(RelationalPersistentProperty property) {
    //                // camelCase → lower_snake_case
    //                return ParsingUtils.reconcatenateCamelCase(
    //                    property.getName(), "_"
    //                ).toLowerCase();
    //            }
    //
    //            @Override
    //            public String getTableName(Class<?> type) {
    //                return type.getSimpleName()
    //                    .replaceAll("([a-z])([A-Z])", "$1_$2")
    //                    .toLowerCase();
    //            }
    //        };
    //    }
    //
    @Override
    public JdbcDialect jdbcDialect(NamedParameterJdbcOperations operations) {
        return JdbcPostgresDialect.INSTANCE;
    }
}
