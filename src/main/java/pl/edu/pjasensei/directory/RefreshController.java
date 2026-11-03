package pl.edu.pjasensei.directory;

import java.util.List;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.edu.pjasensei.identity.TaughtClassGroups;

/** The Refresh button of an Instructor. See {@link Refresh}, which may later also run nightly. */
@RestController
class RefreshController {

    private final Refresh refresh;
    private final TaughtClassGroups taughtClassGroups;

    RefreshController(Refresh refresh, TaughtClassGroups taughtClassGroups) {
        this.refresh = refresh;
        this.taughtClassGroups = taughtClassGroups;
    }

    @PostMapping("/api/refresh")
    RefreshResponse refresh(JwtAuthenticationToken authentication) {
        var caller = new Refresh.Caller(
                authentication.getName(),
                authentication.getToken().getClaimAsString("username"),
                taughtClassGroups.of(authentication.getToken()));
        return new RefreshResponse(refresh.run(caller));
    }

    record RefreshResponse(List<Refresh.Failure> failures) {}
}
