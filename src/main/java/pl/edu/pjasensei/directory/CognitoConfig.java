package pl.edu.pjasensei.directory;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CognitoProperties.class)
class CognitoConfig {

    @Bean
    CognitoIdentityProviderClient cognitoClient(CognitoProperties cognito) {
        var client = CognitoIdentityProviderClient.builder()
                .region(Region.of(cognito.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(cognito.accessKeyId(), cognito.secretAccessKey())))
                .overrideConfiguration(config -> config.apiCallTimeout(Duration.ofSeconds(30)));
        if (StringUtils.hasText(cognito.endpointOverride())) {
            client.endpointOverride(URI.create(cognito.endpointOverride()));
        }
        return client.build();
    }
}
