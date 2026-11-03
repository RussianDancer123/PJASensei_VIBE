package pl.edu.pjasensei.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The real services Spring runs against. One set of them is shared by every test context in
 * the JVM. Images match compose.yaml.
 */
@TestConfiguration(proxyBeanMethods = false)
class TestInfrastructure {

    private static final String GITEA_TOKEN_PROPERTY = "pjasensei.gitea.api-token";

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");
    private static final GiteaContainer GITEA = new GiteaContainer("gitea/gitea:1.27.3");
    private static final TestIdentityProvider IDENTITY_PROVIDER = new TestIdentityProvider();
    private static final TestCognito COGNITO = new TestCognito();

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
    TestGitea testGitea(GiteaContainer gitea) {
        return new TestGitea(gitea);
    }

    @Bean
    TestIdentityProvider identityProvider() {
        return IDENTITY_PROVIDER;
    }

    @Bean
    TestCognito cognito() {
        return COGNITO;
    }

    @Bean
    DynamicPropertyRegistrar cognitoProperties(TestCognito cognito) {
        return registry -> {
            registry.add("pjasensei.cognito.region", () -> TestCognito.REGION);
            registry.add("pjasensei.cognito.user-pool-id", () -> TestCognito.USER_POOL_ID);
            registry.add("pjasensei.cognito.access-key-id", () -> TestCognito.ACCESS_KEY_ID);
            registry.add("pjasensei.cognito.secret-access-key", () -> TestCognito.SECRET_ACCESS_KEY);
            registry.add("pjasensei.cognito.endpoint-override", cognito::endpoint);
        };
    }

    @Bean
    DynamicPropertyRegistrar identityProperties(TestIdentityProvider identityProvider) {
        return registry -> {
            registry.add("pjasensei.identity.issuer", () -> TestIdentityProvider.ISSUER);
            registry.add("pjasensei.identity.jwk-set-uri", identityProvider::jwkSetUri);
            registry.add("pjasensei.identity.client-id", () -> TestIdentityProvider.CLIENT_ID);
        };
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
