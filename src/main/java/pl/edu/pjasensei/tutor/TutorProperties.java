package pl.edu.pjasensei.tutor;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.Assert;
import org.springframework.util.unit.DataSize;

/** Where the AI is and how much a Student may send it in one message. */
@ConfigurationProperties("pjasensei.tutor")
record TutorProperties(String aiUrl, DataSize maxFileSize, DataSize maxMessageSize) {

    TutorProperties {
        Assert.hasText(aiUrl, "pjasensei.tutor.ai-url must be set");
    }
}
