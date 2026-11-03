package pl.edu.pjasensei.identity;

import java.util.List;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/** Which ClassGroups an Instructor may see: only those paired with the groups in their token. */
@Component
public class TaughtClassGroups {

    private final IdentityProperties identity;

    TaughtClassGroups(IdentityProperties identity) {
        this.identity = identity;
    }

    public List<String> of(Jwt token) {
        List<String> groups = token.getClaimAsStringList("cognito:groups");
        if (groups == null) {
            return List.of();
        }
        return groups.stream()
                .filter(identity::isInstructorGroup)
                .map(identity::pairedClassGroup)
                .distinct()
                .toList();
    }
}
