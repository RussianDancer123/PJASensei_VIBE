package pl.edu.pjasensei.directory;

import java.util.regex.Pattern;

/** What counts as a usable email, and its local part, as in {@code s12345} of s12345@mail.com. */
final class Email {

    private static final Pattern USABLE = Pattern.compile("[^@\\s]+@[^@\\s]+");

    private Email() {}

    static boolean isUsable(String email) {
        return email != null && USABLE.matcher(email).matches();
    }

    /** The email has to be usable. */
    static String localPart(String email) {
        return email.substring(0, email.indexOf('@'));
    }
}
