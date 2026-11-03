package pl.edu.pjasensei.support;

import com.github.dockerjava.api.command.InspectContainerResponse;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.ExecConfig;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;

/**
 * A real Gitea set up from the same config file and provisioning script as compose.yaml, so tests
 * exercise what the stack runs rather than a copy of it.
 */
final class GiteaContainer extends GenericContainer<GiteaContainer> {

    private static final int HTTP_PORT = 3000;
    private static final Path CONFIG_FILE = Path.of("docker/gitea/gitea.env");
    private static final Path PROVISIONING_SCRIPT = Path.of("docker/gitea/provision-api-token.sh");
    private static final String SCRIPT_IN_CONTAINER = "/provision-api-token.sh";
    private static final String TOKEN_IN_CONTAINER = "/tmp/gitea-api-token";

    private String apiToken;

    GiteaContainer(String image) {
        super(DockerImageName.parse(image));
        withExposedPorts(HTTP_PORT);
        waitingFor(Wait.forHttp("/api/healthz").forPort(HTTP_PORT));
        withCopyToContainer(Transferable.of(read(PROVISIONING_SCRIPT)), SCRIPT_IN_CONTAINER);
        sharedConfig().forEach((name, value) -> withEnv((String) name, (String) value));
    }

    String apiUrl() {
        return "http://%s:%d".formatted(getHost(), getMappedPort(HTTP_PORT));
    }

    String apiToken() {
        return apiToken;
    }

    @Override
    protected void containerIsStarted(InspectContainerResponse containerInfo) {
        runAsGit("sh", SCRIPT_IN_CONTAINER, "http://localhost:" + HTTP_PORT, TOKEN_IN_CONTAINER);
        apiToken = runAsGit("cat", TOKEN_IN_CONTAINER).strip();
    }

    private String runAsGit(String... command) {
        try {
            ExecResult result = execInContainer(ExecConfig.builder().user("git").command(command).build());
            if (result.getExitCode() != 0) {
                throw new IllegalStateException("%s failed: %s".formatted(command[0], result.getStderr()));
            }
            return result.getStdout();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static Properties sharedConfig() {
        var config = new Properties();
        try (var reader = Files.newBufferedReader(CONFIG_FILE)) {
            config.load(reader);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return config;
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
