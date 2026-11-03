package pl.edu.pjasensei.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The real services Spring runs against. One set of containers is shared by every test context in
 * the JVM. Images match compose.yaml.
 */
@TestConfiguration(proxyBeanMethods = false)
class TestInfrastructure {

    private static final String GITEA_TOKEN_PROPERTY = "pjasensei.gitea.api-token";

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");
    private static final GiteaContainer GITEA = new GiteaContainer("gitea/gitea:1.27.3");

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return POSTGRES;
    }

    @Bean
    GiteaContainer gitea() {
        return GITEA;
    }

    @Bean
    DynamicPropertyRegistrar giteaProperties(GiteaContainer gitea, Environment environment) {
        return registry -> {
            registry.add("pjasensei.gitea.url", gitea::apiUrl);
            // A test may set its own token; dynamic properties would otherwise override it.
            if (!environment.containsProperty(GITEA_TOKEN_PROPERTY)) {
                registry.add(GITEA_TOKEN_PROPERTY, gitea::apiToken);
            }
        };
    }
}
