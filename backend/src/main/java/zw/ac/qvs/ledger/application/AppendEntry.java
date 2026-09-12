package zw.ac.qvs.ledger.application;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import zw.ac.qvs.ledger.domain.AuditEntry;
import zw.ac.qvs.ledger.domain.LedgerAction;
import zw.ac.qvs.shared.domain.Hashing;

/**
 * Records that something happened.
 *
 * <p>Callers must invoke this inside the same transaction as the state change it describes.
 * That is what keeps the ledger from drifting away from the register: either both the
 * credential row and its issuance entry are committed, or neither is. The cost is a
 * serialisation point on sequence assignment, which at this system's scale is irrelevant and
 * which §18 reports as a measured limit rather than hiding.
 */
public class AppendEntry {

    /** Role recorded for an unauthenticated public verification. */
    public static final String ANONYMOUS = "ANONYMOUS";

    private final LedgerRepository ledger;
    private final Clock clock;

    public AppendEntry(LedgerRepository ledger, Clock clock) {
        this.ledger = ledger;
        this.clock = clock;
    }

    /**
     * Appends an entry for an identified actor.
     *
     * @param actorId    who did it
     * @param actorRole  the role they held at the time
     * @param action     what they did
     * @param subjectRef what they did it to
     * @param detail     the event detail; only its hash is stored
     * @return the written entry
     */
    public AuditEntry record(
            UUID actorId, String actorRole, LedgerAction action, String subjectRef, String detail) {
        Instant now = Instant.now(clock);
        return ledger.append(now, actorId, actorRole, action, subjectRef, hashOf(detail));
    }

    /**
     * Appends an entry for an anonymous caller, which is the usual case for a public check.
     *
     * @param action     what happened
     * @param subjectRef what it happened to
     * @param detail     the event detail; only its hash is stored
     * @return the written entry
     */
    public AuditEntry recordAnonymous(LedgerAction action, String subjectRef, String detail) {
        return ledger.append(Instant.now(clock), null, ANONYMOUS, action, subjectRef,
                hashOf(detail));
    }

    /**
     * Hashes the event detail rather than storing it.
     *
     * <p>Two reasons, and the second is the important one. The ledger stays small and of
     * predictable size. And an entry cannot itself become a place where personal data
     * accumulates — a ledger that quoted holder names in its detail column would defeat the
     * minimisation the rest of the design works for. The detail lives in the record it
     * describes; the hash proves it has not changed since.
     */
    private static String hashOf(String detail) {
        return Hashing.sha256Hex(detail == null ? "" : detail);
    }
}
