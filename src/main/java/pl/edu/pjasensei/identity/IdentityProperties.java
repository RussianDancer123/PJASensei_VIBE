package pl.edu.pjasensei.identity;

import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.Assert;

/**
 * Which identity provider's tokens Spring accepts, and which of its groups give a role. The issuer
 * is the public address compared with the token; the signing keys have their own address because
 * the mock's public address does not resolve inside the Compose network. With real Cognito both are
 * the same public address.
 */
@ConfigurationProperties("pjasensei.identity")
record IdentityProperties(
        String issuer,
        String jwkSetUri,
        String clientId,
        Pattern instructorGroupPattern,
        Pattern classGroupPattern) {

    IdentityProperties {
        Assert.hasText(issuer, "pjasensei.identity.issuer must be set");
        Assert.hasText(jwkSetUri, "pjasensei.identity.jwk-set-uri must be set");
        Assert.hasText(clientId, "pjasensei.identity.client-id must be set");
        Assert.notNull(instructorGroupPattern, "pjasensei.identity.instructor-group-pattern must be set");
        Assert.notNull(classGroupPattern, "pjasensei.identity.class-group-pattern must be set");
    }
}
