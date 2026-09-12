package zw.ac.qvs.identity.application;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * The password an account is handed when it is created, and again when it is reset.
 *
 * <p>Generated, never chosen. An administrator typing an initial password picks a memorable
 * one, and a memorable password handed over in an email is the weakest link in an otherwise
 * carefully built chain. Generating it removes the choice; returning it exactly once, and
 * storing only its bcrypt hash, means the system cannot show it again — so it has to be passed
 * on deliberately rather than looked up later.
 *
 * <p>Shared by creation and reset so the two cannot drift into producing passwords of different
 * strengths, which is the kind of difference nobody notices until it matters.
 */
final class InitialPassword {

    /**
     * 18 bytes of entropy, base64url — 24 characters, and guessing is not the attack to worry
     * about. Long enough to be safe, short enough to read down a phone line once.
     */
    private static final int BYTES = 18;

    private static final SecureRandom RANDOM = new SecureRandom();

    private InitialPassword() {
    }

    /**
     * A fresh password.
     *
     * @return 24 URL-safe characters drawn from a cryptographically secure source
     */
    static String generate() {
        byte[] bytes = new byte[BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
