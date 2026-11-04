package pl.edu.pjasensei.tutor;

import java.io.IOException;
import java.io.InputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

/** The plugin's way to the AI: Spring checks whose StudentRepository it is and forwards the rest. */
@RestController
@RequestMapping("/api/student/assignments/{id}/tutor-session/messages")
class TutorSessionController {

    private static final Logger log = LoggerFactory.getLogger(TutorSessionController.class);

    private final TutorSessions sessions;
    private final AiClient ai;
    private final TutorMessageReader messages;

    TutorSessionController(TutorSessions sessions, AiClient ai, TutorMessageReader messages) {
        this.sessions = sessions;
        this.ai = ai;
        this.messages = messages;
    }

    @GetMapping
    JsonNode history(@PathVariable long id, JwtAuthenticationToken authentication) {
        return sessions.withSession(linkOf(id, authentication), ai::history);
    }

    @PostMapping
    JsonNode send(@PathVariable long id, InputStream body, JwtAuthenticationToken authentication) throws IOException {
        var link = linkOf(id, authentication);
        var message = messages.read(body);
        return sessions.withSession(link, session -> ai.sendMessage(session, message));
    }

    private TutorSessions.SessionLink linkOf(long assignment, JwtAuthenticationToken authentication) {
        return sessions.linkOf(assignment, authentication.getName())
                .orElseThrow(() -> new TutorRefused(HttpStatus.NOT_FOUND, "You have no such Assignment."));
    }

    @ExceptionHandler
    ProblemDetail refused(TutorRefused refused) {
        return ProblemDetail.forStatusAndDetail(refused.status(), refused.getMessage());
    }

    @ExceptionHandler
    ProblemDetail aiFailed(RestClientException e) {
        log.warn("The AI did not answer properly", e);
        return ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_GATEWAY, "The tutor cannot answer right now. Your Assignments are not affected.");
    }
}
