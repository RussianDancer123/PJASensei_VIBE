package pl.edu.pjasensei.support;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;

/**
 * An HTTPS host for SourceRepositories that tests control, because a test cannot change anything on
 * GitHub. It serves the repositories of the test Gitea under {@link #NAME}, which only the test Gitea
 * can resolve and which is the one host it may import from.
 */
final class SourceRepositoryHost extends GenericContainer<SourceRepositoryHost> {

    static final String NAME = "source.test";

    SourceRepositoryHost(String image, Network network) {
        super(DockerImageName.parse(image));
        withNetwork(network);
        withNetworkAliases(NAME);
        withExposedPorts(443);
        withCopyToContainer(
                Transferable.of("""
                        %s {
                            tls internal
                            reverse_proxy %s:%d
                        }
                        """.formatted(NAME, GiteaContainer.NETWORK_ALIAS, GiteaContainer.HTTP_PORT)),
                "/etc/caddy/Caddyfile");
    }
}
