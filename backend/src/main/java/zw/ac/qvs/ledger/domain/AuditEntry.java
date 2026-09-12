package zw.ac.qvs.ledger.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * One link in the chain.
 *
 * @param seq         monotonically increasing sequence number, assigned by the database
 * @param occurredAt  when the event happened
 * @param actorId     who did it; null for anonymous public checks
 * @param actorRole   the role they held at the time
 * @param action      what they did
 * @param subjectRef  what they did it to — a credential serial, or a query fingerprint
 * @param payloadHash hash of the event's detail, so the detail can be verified without storing it twice
 * @param prevHash    entry hash of the preceding entry, or the genesis hash for the first
 * @param entryHash   this entry's own hash
 */
public record AuditEntry(
        long seq,
        Instant occurredAt,
        UUID actorId,
        String actorRole,
        LedgerAction action,
        String subjectRef,
        String payloadHash,
        String prevHash,
        String entryHash) {

    public AuditEntry {
        if (occurredAt == null) {
            throw new IllegalArgumentException("occurredAt is required");
        }
        if (actorRole == null || actorRole.isBlank()) {
            throw new IllegalArgumentException("actorRole is required, use ANONYMOUS if unknown");
        }
        if (action == null) {
            throw new IllegalArgumentException("action is required");
        }
        if (subjectRef == null || subjectRef.isBlank()) {
            throw new IllegalArgumentException("subjectRef is required");
        }
        Chain.requireHash(payloadHash, "payloadHash");
        Chain.requireHash(prevHash, "prevHash");
        Chain.requireHash(entryHash, "entryHash");
    }

    /**
     * Recomputes this entry's hash from its own contents.
     *
     * @return the hash this entry should have
     */
    public String recomputeHash() {
        return Chain.entryHash(seq, occurredAt, actorId, actorRole, action, subjectRef,
                payloadHash, prevHash);
    }

    /**
     * Whether the stored hash matches the recomputed one.
     *
     * @return true when this entry has not been altered
     */
    public boolean isSelfConsistent() {
        return Chain.constantTimeEquals(entryHash, recomputeHash());
    }
}
