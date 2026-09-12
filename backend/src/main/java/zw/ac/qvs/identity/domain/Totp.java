package zw.ac.qvs.identity.domain;

import java.nio.ByteBuffer;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Time-based one-time passwords, RFC 6238.
 *
 * <p>Implemented here rather than pulled in as a dependency, for a reason that is worth
 * stating: TOTP is a short, completely specified algorithm with published test vectors, so an
 * implementation can be <em>proved</em> correct against the RFC rather than trusted. The
 * underlying primitive — HMAC — still comes from the JDK. The rule is the same one applied to
 * signing: never write the primitive, and only write the envelope when it is specified tightly
 * enough to test exhaustively.
 *
 * <p>Two properties do real security work and are easy to get wrong. Verification accepts a
 * <b>window</b> of adjacent steps, because a user's phone and the server are never exactly in
 * step; the window is deliberately small, since every extra step is another valid code an
 * attacker can guess. And the comparison is <b>constant-time</b>, because a byte-by-byte
 * comparison of a six-digit code leaks its digits to anyone willing to measure.
 */
public final class Totp {

    /** Seconds per time step, as every authenticator app assumes. */
    public static final int STEP_SECONDS = 30;

    /** Digits in a generated code. */
    public static final int DIGITS = 6;

    /**
     * Steps of clock skew tolerated on either side.
     *
     * <p>One step: up to thirty seconds early or late. Larger windows are common and are a
     * mistake — a window of three multiplies an attacker's guessing odds by seven.
     */
    public static final int WINDOW_STEPS = 1;

    private static final String HMAC = "HmacSHA1";
    private static final int SECRET_BYTES = 20;
    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final int[] POWERS = {1, 10, 100, 1_000, 10_000, 100_000, 1_000_000};

    /** Shared and thread-safe. Constructing a SecureRandom per call re-seeds it every time. */
    private static final SecureRandom RANDOM = new SecureRandom();

    private Totp() {
        // utility
    }

    /**
     * Generates a fresh shared secret, base32-encoded for an authenticator app.
     *
     * @return the secret
     */
    public static String generateSecret() {
        byte[] raw = new byte[SECRET_BYTES];
        RANDOM.nextBytes(raw);
        return base32Encode(raw);
    }

    /**
     * The code for a given secret and instant.
     *
     * @param base32Secret the shared secret
     * @param at           the instant
     * @return a six-digit code, zero-padded
     */
    public static String codeAt(String base32Secret, Instant at) {
        return codeForStep(base32Decode(base32Secret), at.getEpochSecond() / STEP_SECONDS);
    }

    /**
     * Whether a presented code is valid at a given instant.
     *
     * @param base32Secret the shared secret
     * @param presented    the code the user typed
     * @param at           the instant
     * @return true when the code matches this step or an adjacent one
     */
    public static boolean verify(String base32Secret, String presented, Instant at) {
        if (base32Secret == null || presented == null || presented.length() != DIGITS) {
            return false;
        }
        byte[] secret = base32Decode(base32Secret);
        long step = at.getEpochSecond() / STEP_SECONDS;

        boolean matched = false;
        for (long candidate = step - WINDOW_STEPS; candidate <= step + WINDOW_STEPS; candidate++) {
            // Every candidate is evaluated even after a match, so the time taken does not
            // reveal which step matched -- and therefore does not reveal the user's clock skew.
            matched |= constantTimeEquals(codeForStep(secret, candidate), presented);
        }
        return matched;
    }

    private static String codeForStep(byte[] secret, long step) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(secret, HMAC));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(Long.BYTES).putLong(step).array());

            // Dynamic truncation, RFC 4226 section 5.3.
            int offset = hash[hash.length - 1] & 0x0F;
            int binary = ((hash[offset] & 0x7F) << 24)
                    | ((hash[offset + 1] & 0xFF) << 16)
                    | ((hash[offset + 2] & 0xFF) << 8)
                    | (hash[offset + 3] & 0xFF);

            return String.format("%0" + DIGITS + "d", binary % POWERS[DIGITS]);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA1 unavailable", e);
        }
    }

    private static boolean constantTimeEquals(String left, String right) {
        if (left == null || right == null || left.length() != right.length()) {
            return false;
        }
        int difference = 0;
        for (int i = 0; i < left.length(); i++) {
            difference |= left.charAt(i) ^ right.charAt(i);
        }
        return difference == 0;
    }

    static String base32Encode(byte[] data) {
        StringBuilder out = new StringBuilder();
        int buffer = 0;
        int bitsLeft = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                out.append(BASE32.charAt((buffer >> (bitsLeft - 5)) & 0x1F));
                bitsLeft -= 5;
            }
        }
        if (bitsLeft > 0) {
            out.append(BASE32.charAt((buffer << (5 - bitsLeft)) & 0x1F));
        }
        return out.toString();
    }

    static byte[] base32Decode(String encoded) {
        String cleaned = encoded.trim().replace("=", "").toUpperCase(java.util.Locale.ROOT);
        int buffer = 0;
        int bitsLeft = 0;
        byte[] out = new byte[cleaned.length() * 5 / 8];
        int index = 0;

        for (char c : cleaned.toCharArray()) {
            int value = BASE32.indexOf(c);
            if (value < 0) {
                throw new IllegalArgumentException("not a base32 secret");
            }
            buffer = (buffer << 5) | value;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                out[index++] = (byte) ((buffer >> (bitsLeft - 8)) & 0xFF);
                bitsLeft -= 8;
            }
        }
        return out;
    }
}
