package pl.edu.pjasensei.identity;

import java.util.List;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Who the caller is, according to their token alone. */
@RestController
class MeController {

    @GetMapping("/api/me")
    MeResponse me(JwtAuthenticationToken authentication) {
        return new MeResponse(
                authentication.getName(),
                authentication.getToken().getClaimAsString("username"),
                Role.heldBy(authentication));
    }

    record MeResponse(String sub, String username, List<Role> roles) {}
}
