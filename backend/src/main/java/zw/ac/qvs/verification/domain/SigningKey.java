package zw.ac.qvs.verification.domain;

import java.time.LocalDate;
import java.util.UUID;

/**
 * The public half of an institution's signing key, with the interval it was valid for.
 *
 * <p>Decision 05-A made concrete. Every signature carries its {@code kid}, and verification
 * resolves the key that was valid <em>on the award date</em> rather than the key that is
 * current. A credential signed in 2026 therefore still verifies in 2029 after two rotations.
 * Retrofitting this later would mean re-signing the entire register, which is why it is here
 * from the first week rather than the fourth.
 *
 * <p>Only the public half lives in the register. Private keys never leave the vault.
 *
 * @param kid           key identifier, quoted in every signature made with it
 * @param institutionId the institution this key belongs to
 * @param publicJwk     the public key as a JWK document, publishable at a JWKS endpoint
 * @param validFrom     first date this key may sign
 * @param validUntil    last date this key may sign, or null while it is the current key
 */
public record SigningKey(
        String kid,
        UUID institutionId,
        String publicJwk,
        LocalDate validFrom,
        LocalDate validUntil) {

    public SigningKey {
        if (kid == null || kid.isBlank()) {
            throw new IllegalArgumentException("kid is required");
        }
        if (institutionId == null) {
            throw new IllegalArgumentException("a signing key must belong to an institution");
        }
        if (publicJwk == null || publicJwk.isBlank()) {
            throw new IllegalArgumentException("public key material is required");
        }
        if (validFrom == null) {
            throw new IllegalArgumentException("validFrom is required");
        }
        if (validUntil != null && validUntil.isBefore(validFrom)) {
            throw new IllegalArgumentException("validUntil cannot precede validFrom");
        }
    }

    /**
     * Whether this key was in its validity window on a given date.
     *
     * <p>Both ends are inclusive: a key valid until the 30th signed validly on the 30th.
     *
     * @param on the date in question, normally a credential's award date
     * @return true when the key was valid then
     */
    public boolean wasValidOn(LocalDate on) {
        if (on == null) {
            throw new IllegalArgumentException("date is required");
        }
        if (on.isBefore(validFrom)) {
            return false;
        }
        return validUntil == null || !on.isAfter(validUntil);
    }

    /**
     * Whether this is the institution's current key.
     *
     * @return true when the key has no end date
     */
    public boolean isCurrent() {
        return validUntil == null;
    }
}
