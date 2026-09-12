package zw.ac.qvs.ledger.application;

import java.util.List;
import java.util.Optional;
import zw.ac.qvs.ledger.domain.AuditEntry;
import zw.ac.qvs.ledger.domain.Chain;
import zw.ac.qvs.ledger.domain.ChainBreak;

/**
 * Recomputes the chain and reports the first place it stops being trustworthy.
 *
 * <p>This is the operation an auditor runs, and the one the demo runs after editing a row with
 * superuser privileges. It reads the entries and does the arithmetic in the domain; there is
 * no clever SQL doing the verification, because the point is that the check does not depend on
 * the same system that might have been compromised.
 */
public class VerifyChain {

    /** Entries examined per batch, so verifying a long chain does not load it all at once. */
    private static final int BATCH = 5_000;

    private final LedgerRepository ledger;

    public VerifyChain(LedgerRepository ledger) {
        this.ledger = ledger;
    }

    /**
     * Verifies the entire chain from genesis.
     *
     * @return the first break found, or empty when the whole history is intact
     */
    public Optional<ChainBreak> verifyAll() {
        long head = ledger.headSequence();
        if (head == 0) {
            return Optional.empty();
        }

        String previousBatchHash = null;
        for (long from = 1; from <= head; from += BATCH) {
            long to = Math.min(from + BATCH - 1, head);
            List<AuditEntry> batch = ledger.range(from, to);
            if (batch.isEmpty()) {
                continue;
            }

            Optional<ChainBreak> broken = previousBatchHash == null
                    ? Chain.findFirstBreakFromGenesis(batch)
                    : verifyBatchAgainst(previousBatchHash, batch);
            if (broken.isPresent()) {
                return broken;
            }
            previousBatchHash = batch.getLast().entryHash();
        }
        return Optional.empty();
    }

    /**
     * Verifies one credential's slice of history.
     *
     * <p>Note what this cannot do: entries about one subject are not contiguous in the chain,
     * so their links point at unrelated neighbours. This checks that each entry is internally
     * consistent, and callers wanting a full guarantee must run {@link #verifyAll()}.
     *
     * @param subjectRef credential serial
     * @return the first internally inconsistent entry, or empty
     */
    public Optional<ChainBreak> verifySubjectEntries(String subjectRef) {
        return ledger.forSubject(subjectRef).stream()
                .filter(entry -> !entry.isSelfConsistent())
                .findFirst()
                .map(entry -> new ChainBreak(entry.seq(), ChainBreak.Kind.ENTRY_ALTERED,
                        "stored entry_hash does not match the entry's own contents"));
    }

    private Optional<ChainBreak> verifyBatchAgainst(String previousHash, List<AuditEntry> batch) {
        AuditEntry first = batch.getFirst();
        if (!Chain.constantTimeEquals(first.prevHash(), previousHash)) {
            return Optional.of(new ChainBreak(first.seq(), ChainBreak.Kind.LINK_BROKEN,
                    "prev_hash does not match the preceding entry's entry_hash"));
        }
        return Chain.findFirstBreak(batch);
    }
}
