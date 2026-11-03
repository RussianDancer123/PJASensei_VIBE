package pl.edu.pjasensei.directory;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.Assert;

/**
 * How Spring reaches the Cognito API. The endpoint override points the client at another address,
 * such as the mock; left empty, the client uses the real Cognito of the region.
 */
@ConfigurationProperties("pjasensei.cognito")
record CognitoProperties(
        String region, String userPoolId, String accessKeyId, String secretAccessKey, String endpointOverride) {

    CognitoProperties {
        Assert.hasText(region, "pjasensei.cognito.region must be set");
        Assert.hasText(userPoolId, "pjasensei.cognito.user-pool-id must be set");
        Assert.hasText(accessKeyId, "pjasensei.cognito.access-key-id must be set");
        Assert.hasText(secretAccessKey, "pjasensei.cognito.secret-access-key must be set");
    }
}
