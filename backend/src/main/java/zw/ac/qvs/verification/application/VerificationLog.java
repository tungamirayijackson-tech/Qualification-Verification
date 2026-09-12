package zw.ac.qvs.verification.application;

import java.time.Instant;
import java.util.UUID;

/**
 * Outbound port for the record of who checked what.
 *
 * <p>This is the other half of "maintain an auditable history of verification activities". The
 * ledger records that a check happened and makes that record tamper-evident; this table keeps
 * enough detail to answer questions about it later.
 *
 * <p>What it deliberately does not keep: the verifier's address in plaintext, or anything else
 * that would let the register be turned into a history of who took an interest in whom.
 */
public interface VerificationLog {

    /**
     * One verification attempt.
     *
     * @param credentialId the credential checked, or null when the token matched nothing
     * @param channel      WEB, API or QR
     * @param clientIpHash salted hash of the caller's address, or null
     * @param verdict      VALID, REVOKED or NOT_FOUND
     * @param failedCheck  which conjunct failed, or null
     * @param requestedAt  when the check ran
     * @param ledgerSeq    sequence number of the ledger entry recording it
     */
    record Record(
            UUID credentialId,
            String channel,
            String clientIpHash,
            String verdict,
            String failedCheck,
            Instant requestedAt,
            long ledgerSeq) {
    }

    /**
     * Stores one attempt.
     *
     * @param record the attempt
     */
    void record(Record record);

    /**
     * How many checks one address has made inside a window.
     *
     * <p>Feeds the anomaly detection in §15: one address checking two hundred credentials is
     * the signal that someone is working through a list.
     *
     * @param clientIpHash the hashed address
     * @param since        window start
     * @return the count
     */
    long countByClientSince(String clientIpHash, Instant since);
}
