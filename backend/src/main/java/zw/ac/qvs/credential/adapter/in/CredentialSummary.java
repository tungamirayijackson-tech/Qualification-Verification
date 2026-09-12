package zw.ac.qvs.credential.adapter.in;

import java.time.Instant;
import java.time.LocalDate;
import zw.ac.qvs.credential.domain.Credential;

/**
 * What the console shows after a write.
 *
 * @param serial        the human-quotable reference
 * @param awardedOn     when it was conferred
 * @param status        ISSUED or REVOKED
 * @param keyId         which key signed it
 * @param revokedAt     when it was withdrawn, if it was
 * @param revokedReason why, if it was
 * @param issuedAt      when the record was created
 */
public record CredentialSummary(
        String serial,
        LocalDate awardedOn,
        String status,
        String keyId,
        Instant revokedAt,
        String revokedReason,
        Instant issuedAt) {

    static CredentialSummary from(Credential credential) {
        return new CredentialSummary(
                credential.serial().value(),
                credential.awardedOn(),
                credential.status().name(),
                credential.keyId(),
                credential.revokedAt(),
                credential.revokedReason() == null ? null : credential.revokedReason().name(),
                credential.issuedAt());
    }
}
