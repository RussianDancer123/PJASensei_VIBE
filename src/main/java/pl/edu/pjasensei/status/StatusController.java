package pl.edu.pjasensei.status;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.edu.pjasensei.gitea.GiteaClient;

/** Shows whether Spring can reach what it depends on, so a developer can see the stack working. */
@RestController
class StatusController {

    private final JdbcClient jdbc;
    private final GiteaClient giteaClient;

    StatusController(JdbcClient jdbc, GiteaClient giteaClient) {
        this.jdbc = jdbc;
        this.giteaClient = giteaClient;
    }

    @GetMapping("/api/status")
    ResponseEntity<StatusResponse> status() {
        Optional<String> schemaVersion = schemaVersion();
        boolean giteaUp = giteaClient.acceptsOurToken();
        boolean allUp = schemaVersion.isPresent() && giteaUp;
        var body = new StatusResponse(
                Health.of(allUp),
                new DatabaseStatus(Health.of(schemaVersion.isPresent()), schemaVersion.orElse(null)),
                new GiteaStatus(Health.of(giteaUp)));
        return ResponseEntity.status(allUp ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }

    private Optional<String> schemaVersion() {
        try {
            return jdbc.sql("""
                            SELECT version FROM flyway_schema_history
                            WHERE success ORDER BY installed_rank DESC LIMIT 1
                            """)
                    .query(String.class)
                    .optional();
        } catch (DataAccessException e) {
            return Optional.empty();
        }
    }

    enum Health {
        UP,
        DOWN;

        static Health of(boolean up) {
            return up ? UP : DOWN;
        }
    }

    record StatusResponse(Health status, DatabaseStatus database, GiteaStatus gitea) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record DatabaseStatus(Health status, String schemaVersion) {}

    record GiteaStatus(Health status) {}
}
