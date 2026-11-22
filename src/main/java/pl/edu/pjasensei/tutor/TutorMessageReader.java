package pl.edu.pjasensei.tutor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;
import org.springframework.util.unit.DataUnit;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads a message of a Student, refusing what is over the limits or not of the agreed shape. It gives
 * back the bytes as they came, for {@link AiClient} to put into the AI's JSON.
 */
@Component
class TutorMessageReader {

    private static final JsonMapper JSON =
            JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    private final int maxMessageBytes;
    private final long maxFileBytes;
    private final String maxMessageSize;
    private final String maxFileSize;

    TutorMessageReader(TutorProperties properties) {
        this.maxMessageBytes = Math.toIntExact(properties.maxMessageSize().toBytes());
        this.maxFileBytes = properties.maxFileSize().toBytes();
        this.maxMessageSize = readable(properties.maxMessageSize());
        this.maxFileSize = readable(properties.maxFileSize());
    }

    byte[] read(InputStream body) throws IOException {
        // Never more than the limit is held, however much the Student sends.
        var message = body.readNBytes(maxMessageBytes + 1);
        if (message.length > maxMessageBytes) {
            throw new TutorRefused(
                    HttpStatus.CONTENT_TOO_LARGE, "A message may be at most %s.".formatted(maxMessageSize));
        }
        check(parse(message));
        return message;
    }

    private static JsonNode parse(byte[] message) {
        try {
            return JSON.readTree(message);
        } catch (JacksonException e) {
            throw wrongShape();
        }
    }

    private void check(JsonNode message) {
        if (!message.isObject() || !message.path("content").isString()) {
            throw wrongShape();
        }
        if (!message.has("files")) {
            return;
        }
        var files = message.get("files");
        if (!files.isArray()) {
            throw wrongShape();
        }
        for (JsonNode file : files) {
            if (!file.path("path").isString() || !file.path("content").isString()) {
                throw wrongShape();
            }
            if (file.get("content").asString().getBytes(StandardCharsets.UTF_8).length > maxFileBytes) {
                throw new TutorRefused(HttpStatus.CONTENT_TOO_LARGE, "A file may be at most %s.".formatted(maxFileSize));
            }
        }
    }

    private static TutorRefused wrongShape() {
        return new TutorRefused(
                HttpStatus.BAD_REQUEST,
                "A message needs text in \"content\", and each file a \"path\" and text in \"content\".");
    }

    private static String readable(DataSize size) {
        long bytes = size.toBytes();
        long megabyte = DataSize.of(1, DataUnit.MEGABYTES).toBytes();
        return bytes % megabyte == 0 ? "%dMB".formatted(bytes / megabyte) : "%dB".formatted(bytes);
    }
}
