package pl.edu.pjasensei.identity;

import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.Assert;

/**
 * Which identity provider's tokens Spring accepts, and which of its groups give a role. The issuer
 * is the public address compared with the token; the signing keys have their own address because
 * the mock's public address does not resolve inside the Compose network. With real Cognito both are
 * the same public address.
 *
 * <p>A group that gives a role is a number plus a suffix: Instructors of {@code 17p} teach the
 * ClassGroup {@code 17c}, the one with the same number. Any other group is ignored.
 */
@ConfigurationProperties("pjasensei.identity")
record IdentityProperties(
        String issuer,
        String jwkSetUri,
        String instructorGroupSuffix,
        String classGroupSuffix) {

    IdentityProperties {
        Assert.hasText(issuer, "pjasensei.identity.issuer must be set");
        Assert.hasText(jwkSetUri, "pjasensei.identity.jwk-set-uri must be set");
        Assert.hasText(instructorGroupSuffix, "pjasensei.identity.instructor-group-suffix must be set");
        Assert.hasText(classGroupSuffix, "pjasensei.identity.class-group-suffix must be set");
    }

    boolean isInstructorGroup(String group) {
        return isNumberWithSuffix(group, instructorGroupSuffix);
    }

    boolean isClassGroup(String group) {
        return isNumberWithSuffix(group, classGroupSuffix);
    }

    /** The ClassGroup with the same number as this Instructor group. */
    String pairedClassGroup(String instructorGroup) {
        return instructorGroup.substring(0, instructorGroup.length() - instructorGroupSuffix.length()) + classGroupSuffix;
    }

    private static boolean isNumberWithSuffix(String group, String suffix) {
        return group.matches("\\d+" + Pattern.quote(suffix));
    }
}
