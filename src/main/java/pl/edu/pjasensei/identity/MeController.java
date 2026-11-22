package pl.edu.pjasensei.identity;

import java.util.List;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.edu.pjasensei.directory.DisplayNames;

/**
 * Who the caller is, according to their token, and the name to show for them. It asks for no database
 * row, so it answers for people Refresh has not added yet.
 */
@RestController
class MeController {

    private final DisplayNames displayNames;

    MeController(DisplayNames displayNames) {
        this.displayNames = displayNames;
    }

    @GetMapping("/api/me")
    MeResponse me(JwtAuthenticationToken authentication) {
        var username = authentication.getToken().getClaimAsString("username");
        return new MeResponse(
                authentication.getName(),
                username,
                displayNames.of(authentication.getName(), username),
                Role.heldBy(authentication));
    }

    record MeResponse(String sub, String username, String displayName, List<Role> roles) {}
}
