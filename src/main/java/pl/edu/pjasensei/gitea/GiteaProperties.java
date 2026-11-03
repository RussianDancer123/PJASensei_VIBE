package pl.edu.pjasensei.gitea;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.Assert;

/**
 * Where the Gitea API is and the token for it. The stack provisions the token when it starts, so a
 * missing one stops Spring at startup rather than failing on the first call.
 */
@ConfigurationProperties("pjasensei.gitea")
public record GiteaProperties(String url, String apiToken, Duration importTimeout) {

    public GiteaProperties {
        Assert.hasText(url, "pjasensei.gitea.url must be set");
        Assert.hasText(apiToken, "pjasensei.gitea.api-token must be set");
    }
}
