package pl.edu.pjasensei.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * A person at their own computer: a real SSH key pair, and the real git and ssh programs talking to
 * the SSH port of the test Gitea. A clone uses the address exactly as a test was given it. Only where
 * the connection goes is redirected, because Docker chooses the port published for the test Gitea.
 */
public final class GitUser {

    private final Path home;
    private final String publicKey;
    private final String fingerprint;
    private final String sshCommand;

    GitUser(Path home, String gitHost, int gitPort) {
        this.home = home;
        var privateKey = home.resolve("id_ed25519");
        run(home, List.of("ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-C", "git-user", "-f", privateKey.toString()))
                .orThrow();
        try {
            publicKey = Files.readString(home.resolve("id_ed25519.pub")).strip();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        // As ssh-keygen prints it, for example "256 SHA256:abc... git-user (ED25519)".
        fingerprint = run(home, List.of("ssh-keygen", "-l", "-E", "sha256", "-f", privateKey + ".pub"))
                .orThrow()
                .output()
                .strip()
                .split(" ")[1];
        // Git runs this through a shell, which would swallow Windows backslashes.
        sshCommand = ("ssh -F /dev/null -p %d -i '%s' -o HostName=%s -o IdentitiesOnly=yes -o BatchMode=yes"
                        + " -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null")
                .formatted(gitPort, privateKey.toString().replace('\\', '/'), gitHost);
    }

    public String publicKey() {
        return publicKey;
    }

    /** The key's {@code SHA256:...} fingerprint, as ssh-keygen gives it. */
    public String fingerprint() {
        return fingerprint;
    }

    /** Clones into a new directory of this person's computer. */
    public Result gitClone(String sshAddress, String directory) {
        return git(home, "clone", sshAddress, home.resolve(directory).toString());
    }

    /** Adds a file to a clone, commits it and pushes it. */
    public Result pushNewFile(String directory, String file) {
        var clone = home.resolve(directory);
        try {
            Files.writeString(clone.resolve(file), "work of " + publicKey);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        git(clone, "add", file).orThrow();
        git(clone, "-c", "user.name=Git User", "-c", "user.email=git-user@mail.invalid", "commit", "-m", "Add " + file)
                .orThrow();
        return git(clone, "push");
    }

    /** Pushes the commits of one clone to another address, as someone trying another's repository. */
    public Result pushTo(String directory, String sshAddress) {
        return git(home.resolve(directory), "push", sshAddress, "HEAD");
    }

    private Result git(Path directory, String... arguments) {
        var command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(arguments));
        return run(directory, command);
    }

    private Result run(Path directory, List<String> command) {
        var process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true);
        process.environment().put("GIT_TERMINAL_PROMPT", "0");
        if (sshCommand != null) {
            process.environment().put("GIT_SSH_COMMAND", sshCommand);
        }
        try {
            var running = process.start();
            running.getOutputStream().close();
            var output = new String(running.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!running.waitFor(2, TimeUnit.MINUTES)) {
                running.destroyForcibly();
                throw new IllegalStateException("Timed out: " + command);
            }
            return new Result(running.exitValue(), command, output);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    public record Result(int exitCode, List<String> command, String output) {

        public boolean succeeded() {
            return exitCode == 0;
        }

        Result orThrow() {
            if (!succeeded()) {
                throw new IllegalStateException(this.toString());
            }
            return this;
        }
    }
}
