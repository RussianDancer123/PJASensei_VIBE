package pl.edu.pjasensei.directory;

import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserType;

/** What Refresh reads from the identity provider. Any read may throw an {@code SdkException}. */
@Component
class CognitoDirectory {

    private final CognitoIdentityProviderClient cognito;
    private final String userPoolId;

    CognitoDirectory(CognitoIdentityProviderClient cognito, CognitoProperties properties) {
        this.cognito = cognito;
        this.userPoolId = properties.userPoolId();
    }

    /** The email of one person: the access token has none, so it is read here as for Students. */
    Optional<String> emailOf(String username) {
        var user = cognito.adminGetUser(request -> request.userPoolId(userPoolId).username(username));
        return attribute(user.userAttributes(), "email");
    }

    /** All members of a group, across every result page. Returns only once all were read. */
    List<Member> membersOf(String group) {
        return cognito.listUsersInGroupPaginator(request -> request.userPoolId(userPoolId).groupName(group))
                .users()
                .stream()
                .map(CognitoDirectory::member)
                .toList();
    }

    private static Member member(UserType user) {
        return new Member(
                user.username(),
                attribute(user.attributes(), "sub").orElse(null),
                attribute(user.attributes(), "email").orElse(null));
    }

    private static Optional<String> attribute(List<AttributeType> attributes, String name) {
        return attributes.stream()
                .filter(attribute -> name.equals(attribute.name()))
                .map(AttributeType::value)
                .findFirst();
    }

    /** {@code sub} and {@code email} are null when the identity provider has none. */
    record Member(String username, String sub, String email) {

        /** The local part of the email, as in s12345@mail.com. The email has to be usable. */
        String studentNumber() {
            return email.substring(0, email.indexOf('@'));
        }
    }
}
