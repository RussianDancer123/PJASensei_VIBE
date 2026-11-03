package pl.edu.pjasensei.identity;

import java.util.Arrays;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/** Later endpoints require one with Spring Security's {@code hasRole("INSTRUCTOR")}. */
enum Role {
    INSTRUCTOR,
    STUDENT;

    GrantedAuthority authority() {
        return new SimpleGrantedAuthority("ROLE_" + name());
    }

    static List<Role> heldBy(Authentication authentication) {
        return Arrays.stream(values())
                .filter(role -> authentication.getAuthorities().contains(role.authority()))
                .toList();
    }
}
