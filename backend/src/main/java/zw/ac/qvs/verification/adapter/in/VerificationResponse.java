package zw.ac.qvs.verification.adapter.in;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import zw.ac.qvs.verification.application.VerifyCredential;
import zw.ac.qvs.verification.domain.CheckOutcome;
import zw.ac.qvs.verification.domain.CredentialUnderVerification;
import zw.ac.qvs.verification.domain.VerificationCheck;
import zw.ac.qvs.verification.domain.Verdict;

/**
 * What a stranger is told.
 *
 * <p>This type is the enforcement point for NFR-06, so it is worth reading as a list of
 * deliberate omissions rather than as a list of fields. There is no holder name, no national
 * ID, no date of birth, no credential id and no key material. There are initials, which let a
 * verifier holding a CV confirm they are looking at the right person, and there is a ledger
 * sequence number, which lets them cite this exact check in a dispute.
 *
 * <p>The not-found shape is built by a separate factory that fills in nothing but the verdict,
 * so the "no other data leaks on the not-found path" criterion is satisfied by construction
 * rather than by remembering to null fields out.
 *
 * @param verdict       VALID, REVOKED or NOT_FOUND
 * @param checks        per-check outcomes; absent on the not-found path
 * @param qualification what was conferred
 * @param nqfLevel      the level
 * @param institution   who conferred it
 * @param awardedOn     when
 * @param holderInitials the most that is ever disclosed about the person
 * @param revokedReason why it was withdrawn, when it was
 * @param revokedAt     when it was withdrawn
 * @param verifiedAt    when this check ran
 * @param ledgerSeq     the ledger entry recording this check, citable in a dispute
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record VerificationResponse(
        String verdict,
        Map<String, String> checks,
        String qualification,
        Integer nqfLevel,
        String institution,
        LocalDate awardedOn,
        String holderInitials,
        String revokedReason,
        Instant revokedAt,
        Instant verifiedAt,
        Long ledgerSeq) {

    /**
     * Builds the response for an outcome.
     *
     * @param outcome the verification outcome
     * @return the response body
     */
    static VerificationResponse from(VerifyCredential.Outcome outcome) {
        Verdict verdict = outcome.verdict();

        if (verdict instanceof Verdict.NotFound) {
            return notFound(outcome.verifiedAt());
        }

        CredentialUnderVerification credential = outcome.credential();
        String reason = verdict instanceof Verdict.Revoked revoked ? revoked.reason() : null;
        Instant revokedAt = verdict instanceof Verdict.Revoked revoked ? revoked.revokedAt() : null;

        return new VerificationResponse(
                verdict.name(),
                describe(verdict.checks()),
                credential.qualificationTitle(),
                credential.nqfLevel(),
                credential.institutionName(),
                credential.awardedOn(),
                credential.holderInitials(),
                reason,
                revokedAt,
                outcome.verifiedAt(),
                outcome.ledgerSeq());
    }

    /**
     * The one shape returned for every kind of failure to find a valid credential.
     *
     * <p>Nothing here varies with why the lookup failed. An attacker cannot tell a token that
     * never existed from one that expired, was withdrawn, or pointed at a record whose
     * signature no longer verifies.
     */
    private static VerificationResponse notFound(Instant verifiedAt) {
        return new VerificationResponse("NOT_FOUND", null, null, null, null, null, null,
                null, null, verifiedAt, null);
    }

    private static Map<String, String> describe(Map<VerificationCheck, CheckOutcome> checks) {
        if (checks.isEmpty()) {
            return null;
        }
        Map<String, String> described = new LinkedHashMap<>();
        // Fixed order, so the console can render the four checks consistently and a reader
        // comparing two responses is comparing like with like.
        for (VerificationCheck check : VerificationCheck.values()) {
            described.put(camelCase(check), checks.getOrDefault(check, CheckOutcome.NOT_RUN).name());
        }
        return described;
    }

    private static String camelCase(VerificationCheck check) {
        String[] parts = check.name().toLowerCase(java.util.Locale.ROOT).split("_");
        StringBuilder out = new StringBuilder(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            out.append(Character.toUpperCase(parts[i].charAt(0))).append(parts[i].substring(1));
        }
        return out.toString();
    }
}
