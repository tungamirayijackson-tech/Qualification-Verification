package zw.ac.qvs.shared.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 hashing in the one form the system uses: lower-case hex, 64 characters.
 *
 * <p>This lives in the domain deliberately. The audit chain's integrity argument rests on
 * every participant hashing identically, so there is exactly one implementation and it has
 * no Spring, no configuration and no alternate code path.
 */
public final class Hashing {

    /** Length of a SHA-256 digest rendered as lower-case hex. */
    public static final int HEX_LENGTH = 64;

    private static final String ALGORITHM = "SHA-256";

    private Hashing() {
        // utility
    }

    /**
     * Hashes UTF-8 bytes of the given text.
     *
     * @param text value to hash; must not be null
     * @return 64-character lower-case hex digest
     */
    public static String sha256Hex(String text) {
        if (text == null) {
            throw new IllegalArgumentException("cannot hash null");
        }
        return sha256Hex(text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Hashes raw bytes.
     *
     * @param bytes value to hash; must not be null
     * @return 64-character lower-case hex digest
     */
    public static String sha256Hex(byte[] bytes) {
        if (bytes == null) {
            throw new IllegalArgumentException("cannot hash null");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance(ALGORITHM);
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JCA spec; absence means a broken JRE.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Constant-time comparison of two hex digests.
     *
     * <p>Used on the public verification path so that response timing cannot be used as an
     * oracle for whether a token or credential exists.
     *
     * @param left  first digest, may be null
     * @param right second digest, may be null
     * @return true when both are non-null and equal
     */
    public static boolean constantTimeEquals(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return MessageDigest.isEqual(
                left.getBytes(StandardCharsets.UTF_8),
                right.getBytes(StandardCharsets.UTF_8));
    }
}
