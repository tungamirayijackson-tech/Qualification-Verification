package zw.ac.qvs.credential.adapter.in;

import java.time.Instant;
import java.time.LocalDate;
import zw.ac.qvs.verification.domain.CredentialUnderVerification;

/**
 * FR-04: one credential in full, for an authenticated registrar or auditor.
 *
 * <p>Richer than the public verification response, but still not unbounded. The signature and
 * the canonical payload are included deliberately: an auditor must be able to check the
 * signature independently of this system, which is the entire point of publishing the public
 * key. A system that will only tell you its own verdict is asking to be trusted rather than
 * offering to be checked.
 *
 * <p>The displayed holder field is initials — the projection never reads the encrypted name
 * column. <b>The full name is nonetheless present, inside {@code payloadCanonical}</b>, and
 * that is unavoidable rather than an oversight: the signature covers the holder's name, so
 * publishing the signature for independent verification means publishing what it signed. You
 * cannot hand somebody a detached signature and withhold the bytes it is over.
 *
 * <p>So the disclosure boundary this system draws is not "the name never leaves the database".
 * It is that the <em>public</em> path discloses neither the name nor the signed bytes, while
 * the authenticated console — a registrar scoped to that institution, or an auditor — sees a
 * record they are already entitled to see. NFR-06 is a rule about the public surface, and
 * saying so precisely is better than implying a stronger guarantee than the design provides.
 *
 * @param serial           the reference
 * @param institution      who conferred it
 * @param qualification    what was conferred
 * @param nqfLevel         the level
 * @param awardedOn        when
 * @param holderInitials   the holder, minimally
 * @param status           ISSUED or REVOKED
 * @param revokedAt        when it was withdrawn, if it was
 * @param revokedReason    why, if it was
 * @param keyId            the signing key
 * @param detachedJws      the signature, so it can be checked elsewhere
 * @param payloadCanonical the exact bytes the signature covers
 */
public record CredentialDetail(
        String serial,
        String institution,
        String qualification,
        int nqfLevel,
        LocalDate awardedOn,
        String holderInitials,
        String status,
        Instant revokedAt,
        String revokedReason,
        String keyId,
        String detachedJws,
        String payloadCanonical) {

    static CredentialDetail from(CredentialUnderVerification credential) {
        return new CredentialDetail(
                credential.serial(),
                credential.institutionName(),
                credential.qualificationTitle(),
                credential.nqfLevel(),
                credential.awardedOn(),
                credential.holderInitials(),
                credential.revoked() ? "REVOKED" : "ISSUED",
                credential.revokedAt(),
                credential.revokedReason(),
                credential.keyId(),
                credential.detachedJws(),
                credential.payloadCanonical());
    }
}
