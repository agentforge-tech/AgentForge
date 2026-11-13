package cloud.changlu.agentforge.service.database;

import org.mybatis.spring.boot.autoconfigure.ConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.util.Date;

/**
 * Registers the SQLite date handler only for the SQLite profile, leaving MySQL behavior untouched.
 *
 * @author changlu
 * @since 2026-10-29
 */
@Configuration
@Profile("sqlite")
public class SqliteDateTypeHandlerConfiguration {

    @Bean
    public ConfigurationCustomizer sqliteDateTypeHandlerCustomizer() {
        return configuration ->
                configuration
                        .getTypeHandlerRegistry()
                        .register(Date.class, new SqliteDateTypeHandler());
    }
}
