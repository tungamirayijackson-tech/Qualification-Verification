package zw.ac.qvs.credential.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * A signed assertion that an institution conferred a qualification on a holder.
 *
 * <p>Note what this record does not carry: no {@code valid} flag, no cached verdict. Validity
 * is computed at verification time from four independent checks, and a stored boolean would be
 * a fifth source of truth — the first thing to go stale and the last thing anyone would think
 * to distrust.
 *
 * <p>{@code payloadCanonical} holds the exact bytes the signature covers. Storing them, rather
 * than re-deriving them at verification time, removes a whole class of failure: if the
 * canonicalisation rules ever changed, re-derivation would silently produce different bytes
 * and every historical signature would appear to be forged.
 *
 * @param id                surrogate identity
 * @param serial            human-quotable reference
 * @param qualificationId   what was conferred
 * @param holderId          on whom
 * @param awardedOn         when it was conferred
 * @param keyId             which signing key was used
 * @param detachedJws       the detached signature
 * @param payloadCanonical  the exact bytes that were signed
 * @param status            issued or revoked
 * @param revokedAt         when it was withdrawn, null while issued
 * @param revokedReason     why it was withdrawn, null while issued
 * @param revokedBy         who withdrew it, null while issued
 * @param documentObjectKey object-store key for a scanned award document, may be null
 * @param issuedAt          when the record was created
 */
public record Credential(
        UUID id,
        Serial serial,
        UUID qualificationId,
        UUID holderId,
        LocalDate awardedOn,
        String keyId,
        String detachedJws,
        String payloadCanonical,
        CredentialStatus status,
        Instant revokedAt,
        RevocationReason revokedReason,
        UUID revokedBy,
        String documentObjectKey,
        Instant issuedAt) {

    public Credential {
        if (serial == null) {
            throw new IllegalArgumentException("serial is required");
        }
        if (qualificationId == null || holderId == null) {
            throw new IllegalArgumentException("a credential needs a qualification and a holder");
        }
        if (awardedOn == null) {
            throw new IllegalArgumentException("awardedOn is required");
        }
        if (keyId == null || keyId.isBlank()) {
            throw new IllegalArgumentException("a credential must record which key signed it");
        }
        if (detachedJws == null || detachedJws.isBlank()) {
            throw new IllegalArgumentException("a credential must carry its signature");
        }
        if (payloadCanonical == null || payloadCanonical.isBlank()) {
            throw new IllegalArgumentException("a credential must carry the bytes it signed");
        }
        if (status == null) {
            throw new IllegalArgumentException("status is required");
        }
        // Revocation is all-or-nothing. A half-revoked record is how a register and its audit
        // trail start disagreeing, so the type refuses to hold one.
        boolean revoked = status == CredentialStatus.REVOKED;
        if (revoked && (revokedAt == null || revokedReason == null)) {
            throw new IllegalArgumentException("a revoked credential must record when and why");
        }
        if (!revoked && (revokedAt != null || revokedReason != null)) {
            throw new IllegalArgumentException("an issued credential cannot carry revocation detail");
        }
    }

    /**
     * Whether this credential has been withdrawn.
     *
     * @return true when revoked
     */
    public boolean isRevoked() {
        return status == CredentialStatus.REVOKED;
    }

    /**
     * The reason it was withdrawn, if it was.
     *
     * @return the reason
     */
    public Optional<RevocationReason> revocationReason() {
        return Optional.ofNullable(revokedReason);
    }

    /**
     * Returns a withdrawn copy of this credential.
     *
     * <p>The signature and the signed bytes are carried over untouched. That is the point of
     * FR-07: the institution did confer the award, and it later withdrew it. Both remain true,
     * and a verifier is shown both — a revoked credential whose signature still verifies.
     * Destroying the signature on revocation would erase the evidence that the award was ever
     * genuinely made.
     *
     * @param reason why
     * @param at     when
     * @param by     who
     * @return the revoked credential
     */
    public Credential revoked(RevocationReason reason, Instant at, UUID by) {
        if (isRevoked()) {
            throw new IllegalStateException("credential " + serial + " is already revoked");
        }
        if (reason == null) {
            throw new IllegalArgumentException("a revocation must carry a reason");
        }
        if (at == null) {
            throw new IllegalArgumentException("a revocation must carry a timestamp");
        }
        return new Credential(id, serial, qualificationId, holderId, awardedOn, keyId,
                detachedJws, payloadCanonical, CredentialStatus.REVOKED, at, reason, by,
                documentObjectKey, issuedAt);
    }
}
