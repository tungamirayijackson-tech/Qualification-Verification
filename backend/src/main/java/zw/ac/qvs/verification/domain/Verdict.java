package zw.ac.qvs.verification.domain;

import java.time.Instant;
import java.util.Map;

/**
 * What a verifier is told.
 *
 * <p>A sealed interface with exactly three cases, matching FR-06's contract: a public check
 * returns valid, revoked, or not found, and nothing else. Sealing it means a new case cannot
 * be added without every switch in the system failing to compile, which is the property you
 * want on the one type that decides what a stranger learns.
 *
 * <p><b>Why an integrity failure reports as not-found.</b> When the signature, issuer standing
 * or ledger presence check fails, the honest answer to "is there a valid credential behind
 * this token" is no — and saying more than that on an unauthenticated endpoint would tell an
 * attacker which of their forgeries was closest to working. So the stranger sees
 * {@link NotFound}, while the failing check is recorded against the verification request, is
 * appended to the ledger, and is visible to auditors and on the monitoring dashboard. The
 * tampering is not hidden; it is reported to the people whose job it is to act on it, rather
 * than to the person who may have caused it.
 */
public sealed interface Verdict {

    /** The wire name, as it appears in the public response and the database. */
    String name();

    /** Per-check outcomes; empty when disclosing them would leak information. */
    Map<VerificationCheck, CheckOutcome> checks();

    /**
     * All four checks passed.
     *
     * @param checks per-check outcomes, all PASS
     */
    record Valid(Map<VerificationCheck, CheckOutcome> checks) implements Verdict {
        public Valid {
            checks = Map.copyOf(checks);
        }

        @Override
        public String name() {
            return "VALID";
        }
    }

    /**
     * The institution awarded it and later withdrew it.
     *
     * <p>The signature still verifies, and the checks map says so. That is not a contradiction
     * to be tidied away: the award happened, and the withdrawal happened. FR-07 exists to make
     * sure the system can express both at once.
     *
     * @param reason    revocation reason code
     * @param revokedAt when it was withdrawn
     * @param checks    per-check outcomes; SIGNATURE is normally PASS
     */
    record Revoked(String reason, Instant revokedAt, Map<VerificationCheck, CheckOutcome> checks)
            implements Verdict {
        public Revoked {
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("a revocation must carry a reason");
            }
            if (revokedAt == null) {
                throw new IllegalArgumentException("a revocation must carry a timestamp");
            }
            checks = Map.copyOf(checks);
        }

        @Override
        public String name() {
            return "REVOKED";
        }
    }

    /**
     * No valid credential stands behind this token.
     *
     * <p>Returned identically for an unknown token, an expired token, a withdrawn token and a
     * credential that failed an integrity check, so that the endpoint cannot be used to probe
     * which of those is the case. {@code failedCheck} is for the ledger and the auditor; it is
     * never serialised to the caller.
     *
     * @param failedCheck the check that failed, or null when the token was simply unknown
     */
    record NotFound(VerificationCheck failedCheck) implements Verdict {

        /** An unknown or expired token: nothing failed, because nothing was found to check. */
        public static NotFound unknownToken() {
            return new NotFound(null);
        }

        @Override
        public String name() {
            return "NOT_FOUND";
        }

        @Override
        public Map<VerificationCheck, CheckOutcome> checks() {
            // Deliberately empty. Disclosing which conjunct failed would tell an attacker
            // how close a forgery came.
            return Map.of();
        }
    }
}
