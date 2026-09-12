package zw.ac.qvs.verification.domain;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import zw.ac.qvs.shared.domain.Hashing;

/**
 * A holder's grant of permission to check one credential.
 *
 * <p>This is the mechanism behind Decision 09-A. There is no anonymous search anywhere in the
 * system; a verifier can only check a credential whose token the holder handed them. That
 * costs convenience and buys the entire privacy argument, because it means the register cannot
 * be scraped into a dataset of who holds which degree.
 *
 * <p>Three properties, each doing real work. The token is <b>128 bits of randomness</b>, so it
 * cannot be guessed even at scale. It is <b>stored only as a hash</b>, so a database
 * disclosure does not yield working links. And it <b>expires and can be withdrawn</b>, so
 * consent given once for one employer is not consent given forever to everyone they forward
 * it to.
 *
 * @param id            surrogate identity
 * @param credentialId  what it grants access to
 * @param tokenHash     SHA-256 of the token; the token itself is shown once and never stored
 * @param issuedAt      when it was minted
 * @param expiresAt     when it lapses
 * @param revokedAt     when the holder withdrew it, null while live
 * @param label         a note so the holder can tell their tokens apart when revoking one
 */
public record ShareToken(
        UUID id,
        UUID credentialId,
        String tokenHash,
        Instant issuedAt,
        Instant expiresAt,
        Instant revokedAt,
        String label) {

    /** Bytes of entropy in a freshly minted token. */
    public static final int TOKEN_BYTES = 16;

    private static final SecureRandom RANDOM = new SecureRandom();

    public ShareToken {
        if (credentialId == null) {
            throw new IllegalArgumentException("a share token must point at a credential");
        }
        if (tokenHash == null || !tokenHash.matches("^[0-9a-f]{64}$")) {
            throw new IllegalArgumentException("a share token is stored as a SHA-256 hash");
        }
        if (issuedAt == null || expiresAt == null) {
            throw new IllegalArgumentException("a share token must have a lifetime");
        }
        if (!expiresAt.isAfter(issuedAt)) {
            throw new IllegalArgumentException("a share token must expire after it is issued");
        }
    }

    /**
     * A newly minted token: the secret to hand to the holder, and the record to store.
     *
     * @param secret the token itself, shown once and never persisted
     * @param record what goes in the database
     */
    public record Minted(String secret, ShareToken record) {
    }

    /**
     * Mints a token for a credential.
     *
     * @param credentialId what it grants access to
     * @param issuedAt     now
     * @param expiresAt    when it should lapse
     * @param label        a note for the holder
     * @return the secret and the record
     */
    public static Minted mint(
            UUID credentialId, Instant issuedAt, Instant expiresAt, String label) {
        byte[] entropy = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(entropy);
        String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);

        return new Minted(secret, new ShareToken(
                UUID.randomUUID(), credentialId, Hashing.sha256Hex(secret),
                issuedAt, expiresAt, null, label));
    }

    /**
     * Whether this token can still be used.
     *
     * @param now the current instant
     * @return true when it has neither expired nor been withdrawn
     */
    public boolean isUsableAt(Instant now) {
        return revokedAt == null && now.isBefore(expiresAt);
    }

    /**
     * Returns a withdrawn copy.
     *
     * @param at when it was withdrawn
     * @return the revoked token
     */
    public ShareToken revoked(Instant at) {
        return new ShareToken(id, credentialId, tokenHash, issuedAt, expiresAt, at, label);
    }
}
