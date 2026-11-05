package pl.edu.pjasensei.tutor;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.Assert;
import org.springframework.util.unit.DataSize;

/** Where the AI is, how long it may take to answer and how much a Student may send it in one message. */
@ConfigurationProperties("pjasensei.tutor")
record TutorProperties(String aiUrl, Duration readTimeout, DataSize maxFileSize, DataSize maxMessageSize) {

    TutorProperties {
        Assert.hasText(aiUrl, "pjasensei.tutor.ai-url must be set");
    }
}
