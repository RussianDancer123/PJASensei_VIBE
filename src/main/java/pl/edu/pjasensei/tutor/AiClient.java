package pl.edu.pjasensei.tutor;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

/** Spring's only way to the AI, which nothing else may reach. Every call may throw a {@link RestClientException}. */
@Component
@EnableConfigurationProperties(TutorProperties.class)
class AiClient {

    private final RestClient http;

    AiClient(RestClient.Builder builder, TutorProperties properties) {
        this.http = builder.baseUrl(properties.aiUrl()).build();
    }

    String createSession() {
        var session = http.post().uri("/sessions").retrieve().body(NewSession.class);
        if (session == null || session.sessionId() == null || session.sessionId().isBlank()) {
            throw new RestClientException("The AI made a session without an ID.");
        }
        return session.sessionId();
    }

    JsonNode sendMessage(String session, byte[] message) {
        var answer = http.post()
                .uri("/sessions/{id}/messages", session)
                .contentType(MediaType.APPLICATION_JSON)
                .body(message)
                .retrieve()
                .body(JsonNode.class);
        if (answer == null || !answer.path("reply").isString()) {
            throw new RestClientException("The AI answered a message without a reply.");
        }
        return answer;
    }

    JsonNode history(String session) {
        var answer = http.get().uri("/sessions/{id}/messages", session).retrieve().body(JsonNode.class);
        if (answer == null || !answer.path("messages").isArray()) {
            throw new RestClientException("The AI answered for a history without messages.");
        }
        return answer;
    }

    private record NewSession(String sessionId) {}
}
