package zw.ac.qvs.ledger.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import zw.ac.qvs.ledger.domain.AuditEntry;
import zw.ac.qvs.ledger.domain.LedgerAction;

/**
 * Outbound port for the append-only ledger.
 *
 * <p>There is no update and no delete, and there never will be. The port is shaped that way on
 * purpose: an interface that cannot express a mutation cannot have one added by accident in a
 * hurried week, and the database trigger behind it would refuse anyway.
 */
public interface LedgerRepository {

    /**
     * Appends one entry, chained to whatever is currently at the head.
     *
     * <p>Implementations must make reading the head and writing the new entry atomic with
     * respect to other appends. Two concurrent appends that both read the same predecessor
     * would produce two entries claiming the same {@code prev_hash}, which is a fork, and a
     * forked chain cannot be verified.
     *
     * @param occurredAt  when the event happened
     * @param actorId     who did it, or null when anonymous
     * @param actorRole   the role they held
     * @param action      what they did
     * @param subjectRef  what they did it to
     * @param payloadHash hash of the event detail
     * @return the entry as written, including its assigned sequence number and hash
     */
    AuditEntry append(
            Instant occurredAt,
            UUID actorId,
            String actorRole,
            LedgerAction action,
            String subjectRef,
            String payloadHash);

    /**
     * The entry currently at the head of the chain.
     *
     * @return the most recent entry, or empty when the ledger has never been written to
     */
    Optional<AuditEntry> head();

    /**
     * Every entry, in ascending sequence order, within a sequence range.
     *
     * @param fromSeq inclusive lower bound
     * @param toSeq   inclusive upper bound
     * @return entries in ascending order
     */
    List<AuditEntry> range(long fromSeq, long toSeq);

    /**
     * Every entry about one subject, newest first.
     *
     * @param subjectRef credential serial or query fingerprint
     * @return entries, newest first
     */
    List<AuditEntry> forSubject(String subjectRef);

    /**
     * Every entry in a time window, ascending.
     *
     * @param from inclusive start
     * @param to   exclusive end
     * @return entries in ascending sequence order
     */
    List<AuditEntry> between(Instant from, Instant to);

    /**
     * The highest sequence number written so far.
     *
     * @return the head sequence number, or 0 when the ledger is empty
     */
    long headSequence();
}
