package zw.ac.qvs.shared.domain;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Authenticated encryption for the few fields that must be unreadable at rest.
 *
 * <p>Two things use this: the holder's display name, and the private key material in the
 * vault. In both cases the threat is the same and is worth naming precisely — someone who
 * obtains a database dump or a filesystem backup, but not the running application's
 * configuration. It does not defend against an attacker who already has the process's memory
 * or its environment.
 *
 * <p>AES-256-GCM, which is authenticated: a modified ciphertext fails to decrypt rather than
 * decrypting to rubbish. That matters here because a silently corrupted holder name would
 * flow into a signature.
 *
 * <p>A fresh 96-bit IV is generated per encryption and prefixed to the ciphertext. Reusing an
 * IV with the same key in GCM is catastrophic — it leaks the XOR of two plaintexts and allows
 * forgery — so the IV is never derived, cached or supplied by a caller.
 */
public final class FieldCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String ALGORITHM = "AES";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_BYTES = 32;

    /** Shared and thread-safe. Constructing a SecureRandom per call re-seeds it every time. */
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;

    /**
     * Creates a cipher from a base64-encoded 256-bit key.
     *
     * @param base64Key the key, 32 bytes base64-encoded
     */
    public FieldCipher(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalArgumentException("an encryption key is required");
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64Key);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("encryption key must be base64", e);
        }
        if (raw.length != KEY_BYTES) {
            throw new IllegalArgumentException(
                    "encryption key must be 32 bytes (256 bits), got " + raw.length);
        }
        this.key = new SecretKeySpec(raw, ALGORITHM);
    }

    /**
     * Generates a fresh key, for onboarding and for tests.
     *
     * @return a base64-encoded 256-bit key
     */
    public static String generateKey() {
        byte[] raw = new byte[KEY_BYTES];
        RANDOM.nextBytes(raw);
        return Base64.getEncoder().encodeToString(raw);
    }

    /**
     * Encrypts text.
     *
     * @param plaintext the value to protect
     * @return base64 of IV followed by ciphertext and tag
     */
    public String encrypt(String plaintext) {
        if (plaintext == null) {
            throw new IllegalArgumentException("cannot encrypt null");
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            return Base64.getEncoder().encodeToString(
                    ByteBuffer.allocate(iv.length + sealed.length).put(iv).put(sealed).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("field encryption failed", e);
        }
    }

    /**
     * Decrypts text produced by {@link #encrypt}.
     *
     * @param ciphertext base64 of IV followed by ciphertext and tag
     * @return the original plaintext
     * @throws IllegalStateException when the ciphertext was modified or the key is wrong
     */
    public String decrypt(String ciphertext) {
        if (ciphertext == null || ciphertext.isBlank()) {
            throw new IllegalArgumentException("cannot decrypt an empty value");
        }
        byte[] all;
        try {
            all = Base64.getDecoder().decode(ciphertext);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("ciphertext is not valid base64", e);
        }
        if (all.length <= IV_BYTES) {
            throw new IllegalStateException("ciphertext is too short to contain an IV");
        }
        try {
            ByteBuffer buffer = ByteBuffer.wrap(all);
            byte[] iv = new byte[IV_BYTES];
            buffer.get(iv);
            byte[] sealed = new byte[buffer.remaining()];
            buffer.get(sealed);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(sealed), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            // Deliberately does not distinguish "wrong key" from "modified ciphertext":
            // both mean the value cannot be trusted, and telling them apart helps only an
            // attacker probing the store.
            throw new IllegalStateException("field decryption failed", e);
        }
    }
}
