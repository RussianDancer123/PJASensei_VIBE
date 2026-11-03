package pl.edu.pjasensei.identity;

import java.util.function.Supplier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.stereotype.Component;

/**
 * A user missing from the database gets a 403, so no partial account state is exposed. A valid token
 * is not enough: only Refresh adds people to the database. {@link SecurityConfig} applies this to
 * every endpoint it does not exempt, so a new endpoint is covered without doing anything.
 */
@Component
class KnownUserRule implements AuthorizationManager<RequestAuthorizationContext> {

    private final JdbcClient jdbc;

    KnownUserRule(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public AuthorizationResult authorize(
            Supplier<? extends Authentication> authentication, RequestAuthorizationContext request) {
        return new AuthorizationDecision(
                authentication.get() instanceof JwtAuthenticationToken token && isInDatabase(token.getName()));
    }

    private boolean isInDatabase(String sub) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM students WHERE sub = :sub)
                            OR EXISTS (SELECT 1 FROM instructors WHERE sub = :sub)
                        """)
                .param("sub", sub)
                .query(Boolean.class)
                .single();
    }
}
