package pl.edu.pjasensei.directory;

import java.util.List;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.edu.pjasensei.identity.TaughtClassGroups;

/** The ClassGroups the caller teaches, from what the last Refresh stored. No other one appears. */
@RestController
class ClassGroupsController {

    private final DirectoryStore store;
    private final TaughtClassGroups taughtClassGroups;

    ClassGroupsController(DirectoryStore store, TaughtClassGroups taughtClassGroups) {
        this.store = store;
        this.taughtClassGroups = taughtClassGroups;
    }

    @GetMapping("/api/class-groups")
    ClassGroupsResponse classGroups(JwtAuthenticationToken authentication) {
        return new ClassGroupsResponse(taughtClassGroups.of(authentication.getToken()).stream()
                .map(name -> new ClassGroup(name, store.studentsOf(name)))
                .toList());
    }

    record ClassGroupsResponse(List<ClassGroup> classGroups) {}

    record ClassGroup(String name, List<DirectoryStore.Student> students) {}
}
