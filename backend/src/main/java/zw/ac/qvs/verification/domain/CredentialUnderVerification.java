package zw.ac.qvs.verification.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Everything the four checks need about a credential, and nothing else.
 *
 * <p>This is a read model belonging to the verification module, not the credential module's
 * entity. That is a deliberate architectural choice rather than duplication for its own sake.
 * The credential module already depends on this module for signing, so a dependency back the
 * other way would make the two mutually dependent and ArchUnit would fail the build — rightly,
 * because a cycle between the module that issues credentials and the module that judges them
 * is exactly the coupling you do not want.
 *
 * <p>Defining the shape here also means the verification path reads precisely the columns it
 * needs in one query, and cannot accidentally acquire access to holder identity data it has no
 * business seeing. The holder appears here as initials only — the full name never enters this
 * module.
 *
 * @param credentialId      surrogate identity, for logging the verification request
 * @param serial            human-quotable reference
 * @param institutionId     the awarding body
 * @param institutionName   its registered name, shown to the verifier
 * @param accreditedUntil   when its accreditation lapses, for the standing check
 * @param qualificationTitle what was conferred
 * @param nqfLevel          the level conferred
 * @param awardedOn         when it was conferred
 * @param keyId             which key signed it
 * @param detachedJws       the signature
 * @param payloadCanonical  the exact bytes the signature covers
 * @param revoked           whether it has been withdrawn
 * @param revokedAt         when, if it was
 * @param revokedReason     why, if it was
 * @param holderInitials    the most the public path may disclose about the person (NFR-06)
 * @param shareTokenExpired whether the token used to reach this record has lapsed
 * @param shareTokenRevoked whether the holder has withdrawn that token
 */
public record CredentialUnderVerification(
        UUID credentialId,
        String serial,
        UUID institutionId,
        String institutionName,
        LocalDate accreditedUntil,
        String qualificationTitle,
        int nqfLevel,
        LocalDate awardedOn,
        String keyId,
        String detachedJws,
        String payloadCanonical,
        boolean revoked,
        Instant revokedAt,
        String revokedReason,
        String holderInitials,
        boolean shareTokenExpired,
        boolean shareTokenRevoked) {

    /**
     * Whether the institution held accreditation on the award date.
     *
     * <p>On the award date, not today. A degree conferred in 2020 by an institution whose
     * accreditation lapsed in 2024 is still a real degree, and answering otherwise would
     * invalidate every graduate of every closed institution.
     *
     * @return true when the issuer had standing when it made the award
     */
    public boolean issuerHadStandingOnAwardDate() {
        return accreditedUntil != null && !awardedOn.isAfter(accreditedUntil);
    }

    /**
     * Whether the share token that reached this record is still usable.
     *
     * @return true when the token has neither expired nor been withdrawn
     */
    public boolean shareTokenUsable() {
        return !shareTokenExpired && !shareTokenRevoked;
    }
}
