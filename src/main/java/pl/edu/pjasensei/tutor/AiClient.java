package pl.edu.pjasensei.tutor;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Spring's only way to the AI, which nothing else may reach, and the only place that knows the AI's JSON:
 * the plugin keeps {@code content}, {@code reply} and the roles {@code student} and {@code tutor}. Every
 * call may throw a {@link RestClientException}.
 */
@Component
@EnableConfigurationProperties(TutorProperties.class)
class AiClient {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    // TODO: temporary, every TutorSession gets this same lab. It should come from the Assignment.
    private static final String NEW_SESSION = """
            {
              "config": {
                "language": "pl",
                "evaluationCriteria": "Student samodzielnie dochodzi do poprawnego rozwiązania.",
                "learningContext": {
                  "goals": [{ "id": "assignment", "description": "Wykonać zadanie z repozytorium." }]
                },
                "agentPersona": {},
                "agentBehavior": {}
              }
            }
            """;

    private final RestClient http;

    AiClient(
            RestClient.Builder builder,
            TutorProperties properties,
            ClientHttpRequestFactoryBuilder<?> requests,
            HttpClientSettings settings) {
        this.http = builder.baseUrl(properties.aiUrl())
                .requestFactory(requests.build(settings.withReadTimeout(properties.readTimeout())))
                .build();
    }

    String createSession() {
        var session = http.post()
                .uri("/conversations")
                .contentType(MediaType.APPLICATION_JSON)
                .body(NEW_SESSION)
                .retrieve()
                .body(JsonNode.class);
        var id = session == null ? "" : session.path("conversation_id").asString("");
        if (id.isBlank()) {
            throw new RestClientException("The AI made a session without an ID.");
        }
        return id;
    }

    JsonNode sendMessage(String session, byte[] message) {
        var question = (ObjectNode) JSON.readTree(message);
        question.set("question", question.remove("content"));
        withCodeContext(question);
        var answer = http.post()
                .uri("/conversations/{id}/messages", session)
                .contentType(MediaType.APPLICATION_JSON)
                .body(JSON.writeValueAsBytes(question))
                .retrieve()
                .body(JsonNode.class);
        if (answer == null || !answer.path("answer").isString()) {
            throw new RestClientException("The AI answered a message without an answer.");
        }
        var reply = (ObjectNode) answer;
        reply.set("reply", reply.remove("answer"));
        return reply;
    }

    // TODO: temporary — remove when the AI reads files. Until then it reads only the file open in the editor,
    // which the plugin always sends first, as code_context, and the names of the other files.
    private static void withCodeContext(ObjectNode question) {
        var files = question.remove("files");
        if (files == null || files.isEmpty()) {
            return;
        }
        var open = files.get(0);
        var context = question.putObject("code_context");
        context.set("current_file_name", open.get("path"));
        context.set("current_code", open.get("content"));
        var others = context.putArray("open_files");
        for (int i = 1; i < files.size(); i++) {
            others.add(files.get(i).get("path"));
        }
    }

    JsonNode history(String session) {
        var answer = http.get().uri("/conversations/{id}/messages", session).retrieve().body(JsonNode.class);
        if (answer == null || !answer.path("messages").isArray()) {
            throw new RestClientException("The AI answered for a history without messages.");
        }
        for (JsonNode message : answer.get("messages")) {
            if (message instanceof ObjectNode known) {
                known.put("role", message.path("role").asString("").equals("user") ? "student" : "tutor");
            }
        }
        return answer;
    }
}
