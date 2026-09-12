package zw.ac.qvs.testsupport;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import zw.ac.qvs.ledger.application.LedgerRepository;
import zw.ac.qvs.ledger.domain.AuditEntry;
import zw.ac.qvs.ledger.domain.Chain;
import zw.ac.qvs.ledger.domain.LedgerAction;

/**
 * The audit ledger in memory, with a real hash chain.
 *
 * <p>Shared rather than copied. It began as a private class inside one test, was about to be
 * copied into a second and then a third, and three definitions of a chain is how two of them
 * end up subtly different — at which point a test passes because its own copy of the ledger
 * agrees with it.
 *
 * <p>It computes hashes with the production {@link Chain}, so a test asserting that an entry
 * links to its predecessor is asserting something true rather than something this class made
 * up. That matters: the chain is the property the whole audit story rests on.
 *
 * <p>No mocking framework, in keeping with the rest of the suite. A hand-written stub is longer
 * and says exactly what it does.
 */
public final class InMemoryLedger implements LedgerRepository {

    private final List<AuditEntry> entries = new ArrayList<>();

    /** Everything appended so far, oldest first. */
    public List<AuditEntry> entries() {
        return List.copyOf(entries);
    }

    /** The entries for one action, which is usually what a test is actually asking about. */
    public List<AuditEntry> withAction(LedgerAction action) {
        return entries.stream().filter(entry -> entry.action() == action).toList();
    }

    @Override
    public AuditEntry append(Instant occurredAt, UUID actorId, String actorRole,
            LedgerAction action, String subjectRef, String payloadHash) {

        long seq = entries.size() + 1L;
        String previous = entries.isEmpty() ? Chain.GENESIS : entries.getLast().entryHash();
        String hash = Chain.entryHash(
                seq, occurredAt, actorId, actorRole, action, subjectRef, payloadHash, previous);

        AuditEntry entry = new AuditEntry(
                seq, occurredAt, actorId, actorRole, action, subjectRef, payloadHash,
                previous, hash);
        entries.add(entry);
        return entry;
    }

    @Override
    public Optional<AuditEntry> head() {
        return entries.isEmpty() ? Optional.empty() : Optional.of(entries.getLast());
    }

    @Override
    public List<AuditEntry> range(long fromSeq, long toSeq) {
        return entries.stream()
                .filter(entry -> entry.seq() >= fromSeq && entry.seq() <= toSeq)
                .toList();
    }

    @Override
    public List<AuditEntry> forSubject(String subjectRef) {
        return entries.stream()
                .filter(entry -> entry.subjectRef().equals(subjectRef))
                .toList();
    }

    @Override
    public List<AuditEntry> between(Instant from, Instant to) {
        return entries.stream()
                .filter(entry -> !entry.occurredAt().isBefore(from)
                        && entry.occurredAt().isBefore(to))
                .toList();
    }

    @Override
    public long headSequence() {
        return entries.size();
    }
}
