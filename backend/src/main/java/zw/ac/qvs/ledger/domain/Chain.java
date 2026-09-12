package zw.ac.qvs.ledger.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import zw.ac.qvs.shared.domain.Hashing;

/**
 * The hash chain: how the ledger proves it was not edited.
 *
 * <p>Each entry stores the hash of its predecessor, so altering entry <i>n</i> invalidates
 * every hash from <i>n</i> onward. A tamperer must rewrite the entire tail, consistently, and
 * an auditor detects it by recomputing one chain.
 *
 * <p>This does not <em>prevent</em> tampering. Nothing inside a single-writer database can: a
 * superuser who can edit rows can also re-chain them. What it does is make tampering
 * <em>evident</em>, and name the exact row where the history stops being trustworthy. That is
 * a smaller claim than "immutable", and it is the one this design can actually support.
 */
public final class Chain {

    /** The {@code prev_hash} of the first entry: sixty-four zeroes. */
    public static final String GENESIS = "0".repeat(Hashing.HEX_LENGTH);

    private Chain() {
        // utility
    }

    /**
     * Computes an entry hash.
     *
     * <p>Fields are length-prefixed rather than joined by a separator. This matters more than
     * it looks. With a plain pipe-separated join, the field lists {@code ["a|b", "c"]} and
     * {@code ["a", "b|c"]} produce identical input and therefore identical hashes, so two
     * different histories could share a hash. Length prefixing makes the encoding injective,
     * which is the property the whole chain rests on.
     *
     * @param seq         sequence number
     * @param occurredAt  event time
     * @param actorId     actor, or null when anonymous
     * @param actorRole   role held at the time
     * @param action      what happened
     * @param subjectRef  what it happened to
     * @param payloadHash hash of the event detail
     * @param prevHash    preceding entry's hash, or {@link #GENESIS}
     * @return 64-character lower-case hex digest
     */
    public static String entryHash(
            long seq,
            Instant occurredAt,
            UUID actorId,
            String actorRole,
            LedgerAction action,
            String subjectRef,
            String payloadHash,
            String prevHash) {

        StringBuilder input = new StringBuilder(256);
        appendField(input, Long.toString(seq));
        appendField(input, occurredAt.toString());
        appendField(input, actorId == null ? "" : actorId.toString());
        appendField(input, actorRole);
        appendField(input, action.name());
        appendField(input, subjectRef);
        appendField(input, payloadHash);
        appendField(input, prevHash);
        return Hashing.sha256Hex(input.toString());
    }

    private static void appendField(StringBuilder target, String value) {
        target.append(value.length()).append(':').append(value).append('|');
    }

    /**
     * Walks a run of entries and reports the first place the history stops being trustworthy.
     *
     * <p>Two things are checked at every step, because they catch different attacks. An entry
     * whose stored hash does not match its own contents has been <b>edited</b>. An entry whose
     * {@code prev_hash} does not match its predecessor's hash means one has been
     * <b>inserted, removed or reordered</b>.
     *
     * @param entries entries in ascending sequence order
     * @return the first break found, or empty when the run is intact
     */
    public static Optional<ChainBreak> findFirstBreak(List<AuditEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return Optional.empty();
        }

        AuditEntry previous = null;
        for (AuditEntry entry : entries) {
            if (!entry.isSelfConsistent()) {
                return Optional.of(new ChainBreak(
                        entry.seq(), ChainBreak.Kind.ENTRY_ALTERED,
                        "stored entry_hash does not match the entry's own contents"));
            }
            if (previous != null) {
                if (entry.seq() <= previous.seq()) {
                    return Optional.of(new ChainBreak(
                            entry.seq(), ChainBreak.Kind.OUT_OF_ORDER,
                            "sequence numbers are not strictly ascending"));
                }
                if (!constantTimeEquals(entry.prevHash(), previous.entryHash())) {
                    return Optional.of(new ChainBreak(
                            entry.seq(), ChainBreak.Kind.LINK_BROKEN,
                            "prev_hash does not match the preceding entry's entry_hash"));
                }
            }
            previous = entry;
        }
        return Optional.empty();
    }

    /**
     * Validates that a run starting at the very beginning is anchored to genesis.
     *
     * <p>Without this, a tamperer could delete the first thousand entries and re-chain the
     * remainder into a perfectly self-consistent history that simply starts later.
     *
     * @param entries entries in ascending order, starting at the chain's first entry
     * @return the break found, or empty
     */
    public static Optional<ChainBreak> findFirstBreakFromGenesis(List<AuditEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return Optional.empty();
        }
        AuditEntry first = entries.getFirst();
        if (!constantTimeEquals(first.prevHash(), GENESIS)) {
            return Optional.of(new ChainBreak(
                    first.seq(), ChainBreak.Kind.NOT_ANCHORED,
                    "the first entry does not link to the genesis hash"));
        }
        return findFirstBreak(entries);
    }

    /**
     * Compares two hex digests without leaking their contents through timing.
     *
     * @param left  first digest
     * @param right second digest
     * @return true when both are non-null and equal
     */
    public static boolean constantTimeEquals(String left, String right) {
        return Hashing.constantTimeEquals(left, right);
    }

    /**
     * Validates the shape of a hash field.
     *
     * @param value the value to check
     * @param field field name, for the error message
     */
    static void requireHash(String value, String field) {
        boolean wellFormed = value != null
                && value.length() == Hashing.HEX_LENGTH
                && value.chars().allMatch(Chain::isLowerHex);
        if (!wellFormed) {
            throw new IllegalArgumentException(
                    field + " must be 64 lower-case hex characters, got: " + value);
        }
    }

    private static boolean isLowerHex(int c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
    }
}
