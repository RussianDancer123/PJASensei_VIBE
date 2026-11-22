package pl.edu.pjasensei.gitea;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Spring's only way into Gitea, which nothing else may reach. */
@Component
@EnableConfigurationProperties(GiteaProperties.class)
public class GiteaClient {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String TEMPLATE_INSTRUCTIONS = ".gitea/template";
    private static final Pattern PUB_FILE_LINE = Pattern.compile("\\S+[ \\t]+[A-Za-z0-9+/]+={0,2}([ \\t].*)?");
    private static final Pattern README_NAME =
            Pattern.compile("readme(\\.(md|markdown|txt|rst|adoc|org))?", Pattern.CASE_INSENSITIVE);
    private static final String PREFERRED_README = "README.md";
    private static final long MAX_README_BYTES = 1024 * 1024;
    private static final int KEYS_PER_PAGE = 50;

    private final RestClient http;
    private final RestClient importHttp;

    GiteaClient(RestClient.Builder builder, GiteaProperties properties) {
        builder.baseUrl(properties.url())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "token " + properties.apiToken());
        this.http = builder.build();
        // Gitea answers an import only when it has copied the repository, which can take a while.
        this.importHttp = builder.clone()
                .requestFactory(ClientHttpRequestFactoryBuilder.detect()
                        .build(HttpClientSettings.defaults()
                                .withTimeouts(Duration.ofSeconds(5), properties.importTimeout())))
                .build();
    }

    /** Whether Gitea answers and accepts the token: this endpoint is refused without a valid one. */
    public boolean acceptsOurToken() {
        try {
            http.get().uri("/api/v1/user").retrieve().toBodilessEntity();
            return true;
        } catch (RestClientException e) {
            return false;
        }
    }

    /**
     * Makes sure a person has their technical account, safe to repeat and to run at the same time as
     * another call. It is named after the {@code sub}, the one identifier Students and Instructors
     * both have and that never changes, so a person with both roles has one account. Nobody signs in
     * with it: the password goes nowhere, and the address only fills a required field.
     *
     * @throws RestClientException when Gitea cannot or will not make the account
     */
    public void ensureAccount(String sub) {
        if (accountExists(sub)) {
            return;
        }
        try {
            http.post()
                    .uri("/api/v1/admin/users")
                    .body(Map.of(
                            "username", sub,
                            "email", sub + "@pjasensei.invalid",
                            "password", randomPassword(),
                            "must_change_password", false))
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException e) {
            // Another call may have made it in between; otherwise Gitea refused for a real reason.
            if (!accountExists(sub)) {
                throw e;
            }
        }
    }

    /**
     * Puts an SSH key on an account, which may hold several. Safe to repeat, so a plugin can register
     * its key every time it starts.
     *
     * @throws RestClientException when Gitea cannot answer
     */
    public KeyResult addPublicKey(String account, String publicKey) {
        // Gitea also takes other forms, but a repeat could then not be recognised by its fingerprint.
        var key = publicKey.strip();
        if (!PUB_FILE_LINE.matcher(key).matches()) {
            return KeyResult.INVALID;
        }
        try {
            http.post()
                    .uri("/api/v1/admin/users/{account}/keys", account)
                    // Gitea needs a title unique to the account, which nobody ever sees.
                    .body(Map.of("title", "key-" + UUID.randomUUID(), "key", key))
                    .retrieve()
                    .toBodilessEntity();
            return KeyResult.ADDED;
        } catch (HttpClientErrorException.UnprocessableContent e) {
            // Only the words of Gitea's answer tell a key already in use from an invalid one.
            if (!e.getResponseBodyAsString().contains("has been used")) {
                return KeyResult.INVALID;
            }
            return accountHasKey(account, key) ? KeyResult.ADDED : KeyResult.USED_BY_ANOTHER_ACCOUNT;
        }
    }

    /**
     * The SSH keys on an account, oldest first. An account that does not exist has none, and is not made.
     *
     * @throws RestClientException when Gitea cannot answer
     */
    public List<PublicKey> publicKeys(String account) {
        var keys = new ArrayList<PublicKey>();
        for (int page = 1; ; page++) {
            List<PublicKey> found;
            try {
                found = http.get()
                        .uri("/api/v1/users/{account}/keys?page={page}&limit={limit}", account, page, KEYS_PER_PAGE)
                        .retrieve()
                        .body(new ParameterizedTypeReference<>() {});
            } catch (HttpClientErrorException.NotFound e) {
                return List.of();
            }
            // Gitea may give fewer than asked for on every page, so only an empty one says the list is over.
            if (found.isEmpty()) {
                break;
            }
            keys.addAll(found);
        }
        keys.sort(Comparator.comparing(PublicKey::createdAt).thenComparing(PublicKey::id));
        return keys;
    }

    /**
     * Removes one SSH key of an account. A key of another account is left alone, as is one that does not
     * exist.
     *
     * @return whether the account had that key
     * @throws RestClientException when Gitea cannot or will not remove it
     */
    public boolean deletePublicKey(String account, long id) {
        if (publicKeys(account).stream().noneMatch(key -> key.id() == id)) {
            return false;
        }
        try {
            http.delete()
                    .uri("/api/v1/admin/users/{account}/keys/{id}", account, id)
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (HttpClientErrorException.NotFound e) {
            // Removed in the meantime.
            return false;
        }
    }

    private boolean accountHasKey(String account, String publicKey) {
        return !http.get()
                .uri("/api/v1/users/{account}/keys?fingerprint={fingerprint}", account, fingerprintOf(publicKey))
                .retrieve()
                .body(new ParameterizedTypeReference<List<Object>>() {})
                .isEmpty();
    }

    /** Of a key Gitea has already found valid, in the form its lookup by fingerprint expects. */
    private static String fingerprintOf(String publicKey) {
        var keyBytes = Base64.getDecoder().decode(publicKey.split("[ \\t]+")[1]);
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(keyBytes);
            return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Copies a SourceRepository once into a private repository, to become a snapshot. A failure leaves
     * nothing behind as far as possible, since a timeout says nothing about whether the copy worked.
     *
     * @return the name of the repository, which {@link #prepareSnapshot} has to finish
     * @throws RestClientException when Gitea cannot or will not copy it
     */
    public String importSource(String sourceUrl) {
        var name = "snapshot-" + UUID.randomUUID();
        try {
            importHttp.post()
                    .uri("/api/v1/repos/migrate")
                    .body(Map.of("clone_addr", sourceUrl, "repo_name", name, "service", "git", "private", true))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            try {
                deleteRepository(name);
            } catch (RestClientException notDeleted) {
                e.addSuppressed(notDeleted);
            }
            throw e;
        }
        return name;
    }

    /**
     * Turns that repository into the snapshot StudentRepositories start from. A template, because that is
     * how Gitea makes a repository with the same files and a new history.
     *
     * @throws RestClientException when Gitea cannot or will not do it
     */
    public void prepareSnapshot(String name) {
        http.patch()
                .uri("/api/v1/repos/{owner}/{name}", serviceAccount(), name)
                .body(Map.of("template", true))
                .retrieve()
                .toBodilessEntity();
        removeTemplateInstructions(name);
    }

    /**
     * Removes a repository of Spring's account, which may already be gone.
     *
     * @throws RestClientException when Gitea cannot or will not remove it
     */
    public void deleteRepository(String name) {
        try {
            http.delete()
                    .uri("/api/v1/repos/{owner}/{name}", serviceAccount(), name)
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException.NotFound e) {
            // Nothing to remove.
        }
    }

    /**
     * Gitea would follow this file when making a repository from the snapshot, filling in the name of
     * each repository, so Students would start from different files.
     */
    private void removeTemplateInstructions(String snapshot) {
        File instructions;
        try {
            instructions = http.get()
                    .uri(
                            "/api/v1/repos/{owner}/{name}/contents/{path}",
                            serviceAccount(),
                            snapshot,
                            TEMPLATE_INSTRUCTIONS)
                    .retrieve()
                    .body(File.class);
        } catch (HttpClientErrorException.NotFound e) {
            return;
        }
        http.method(HttpMethod.DELETE)
                .uri(
                        "/api/v1/repos/{owner}/{name}/contents/{path}",
                        serviceAccount(),
                        snapshot,
                        TEMPLATE_INSTRUCTIONS)
                .body(Map.of("sha", instructions.sha()))
                .retrieve()
                .toBodilessEntity();
    }

    /**
     * Makes a private repository from a snapshot, with a clean history. Spring's account owns it,
     * because an owner could do more than the write access a Student is meant to have.
     *
     * <p>A repository of exactly this name counts as made, as an earlier attempt may have made it and
     * then timed out. Gitea ignores case in names, so one differing in case is someone else's.
     *
     * @return the address to clone it from over SSH
     * @throws RestClientException when Gitea cannot or will not make it
     */
    public String createFromSnapshot(String snapshot, String name) {
        var owner = serviceAccount();
        try {
            return http.post()
                    .uri("/api/v1/repos/{owner}/{name}/generate", owner, snapshot)
                    .body(Map.of("owner", owner, "name", name, "private", true, "git_content", true))
                    .retrieve()
                    .body(Repository.class)
                    .sshUrl();
        } catch (HttpClientErrorException.Conflict e) {
            var existing = http.get()
                    .uri("/api/v1/repos/{owner}/{name}", owner, name)
                    .retrieve()
                    .body(Repository.class);
            if (!name.equals(existing.name())) {
                throw e;
            }
            return existing.sshUrl();
        }
    }

    /** @throws RestClientException when Gitea cannot or will not give it */
    public void grantWrite(String repository, String account) {
        grant(repository, account, "write");
    }

    /** @throws RestClientException when Gitea cannot or will not give it */
    public void grantRead(String repository, String account) {
        grant(repository, account, "read");
    }

    private void grant(String repository, String account, String permission) {
        http.put()
                .uri(
                        "/api/v1/repos/{owner}/{name}/collaborators/{account}",
                        serviceAccount(),
                        repository,
                        account)
                .body(Map.of("permission", permission))
                .retrieve()
                .toBodilessEntity();
    }

    private String serviceAccount() {
        return http.get().uri("/api/v1/user").retrieve().body(Account.class).login();
    }

    /**
     * The text of the README at the top of a repository, unrendered. A Student can push anything to
     * their StudentRepository, so a README too big to hold in memory is treated as missing.
     *
     * @throws RestClientException when Gitea cannot answer
     */
    public Optional<String> readme(String repository) {
        var owner = serviceAccount();
        List<Entry> entries;
        try {
            entries = http.get()
                    .uri("/api/v1/repos/{owner}/{name}/contents", owner, repository)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        }
        return entries.stream()
                .filter(Entry::isReadme)
                .min(Comparator.comparing((Entry entry) -> !entry.name().equalsIgnoreCase(PREFERRED_README))
                        .thenComparing(Entry::name))
                .filter(readme -> readme.size() <= MAX_README_BYTES)
                .flatMap(readme -> readFile(owner, repository, readme.name()));
    }

    /** As bytes, because Gitea does not always name the encoding and this would then be misread. */
    private Optional<String> readFile(String owner, String repository, String path) {
        try {
            var content = http.get()
                    .uri("/api/v1/repos/{owner}/{name}/raw/{path}", owner, repository, path)
                    .retrieve()
                    .body(byte[].class);
            return Optional.of(new String(content, StandardCharsets.UTF_8));
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        }
    }

    public enum KeyResult {
        /** The account has the key now, whether or not it had it already. */
        ADDED,
        INVALID,
        USED_BY_ANOTHER_ACCOUNT
    }

    /** An SSH key as Gitea knows it, with a fingerprint in the {@code SHA256:...} form. */
    public record PublicKey(long id, String fingerprint, @JsonProperty("created_at") OffsetDateTime createdAt) {}

    private record Account(String login) {}

    private record File(String sha) {}

    private record Entry(String name, String type, long size) {

        boolean isReadme() {
            return "file".equals(type) && README_NAME.matcher(name).matches();
        }
    }

    private record Repository(String name, @JsonProperty("ssh_url") String sshUrl) {}

    private boolean accountExists(String name) {
        try {
            http.get().uri("/api/v1/users/{name}", name).retrieve().toBodilessEntity();
            return true;
        } catch (HttpClientErrorException.NotFound e) {
            return false;
        }
    }

    private static String randomPassword() {
        var bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
