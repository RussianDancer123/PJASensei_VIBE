package pl.edu.pjasensei.assignment;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The {@code owner/name} of a repository, taken from a git remote address in either of its SSH forms:
 * {@code ssh://user@host[:port]/owner/name[.git]} or {@code user@host:owner/name[.git]}. The host and
 * the port are dropped, and the letter case too, as Gitea ignores it in a repository path.
 */
final class GitRemote {

    private static final Pattern URL_FORM = Pattern.compile("ssh://[^/ ]+/(?<path>[^ ]+)");
    private static final Pattern SCP_FORM = Pattern.compile("(?:[^/@: ]+@)?[^/@: ]+:(?<path>[^/: ][^ ]*)");
    private static final Pattern OWNER_AND_NAME = Pattern.compile("[^/ ]+/[^/ ]+");

    private GitRemote() {}

    /** Empty when the address is in neither form, or does not name exactly {@code owner/name}. */
    static Optional<String> ownerAndName(String address) {
        var trimmed = address.strip();
        var matcher = URL_FORM.matcher(trimmed);
        if (!matcher.matches()) {
            matcher = SCP_FORM.matcher(trimmed);
            if (!matcher.matches()) {
                return Optional.empty();
            }
        }
        var path = matcher.group("path");
        if (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        if (path.toLowerCase(Locale.ROOT).endsWith(".git")) {
            path = path.substring(0, path.length() - ".git".length());
        }
        return OWNER_AND_NAME.matcher(path).matches() ? Optional.of(path.toLowerCase(Locale.ROOT)) : Optional.empty();
    }
}
