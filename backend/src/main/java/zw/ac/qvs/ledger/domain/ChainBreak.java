package zw.ac.qvs.ledger.domain;

/**
 * Where and how a chain stopped being trustworthy.
 *
 * <p>Naming the exact sequence number is the point. "The audit log may have been tampered
 * with" is not actionable; "the history is trustworthy up to seq 4182 and not after it" is.
 *
 * @param atSeq  the sequence number where the break was detected
 * @param kind   what sort of break it is
 * @param detail human-readable explanation
 */
public record ChainBreak(long atSeq, Kind kind, String detail) {

    /** The kinds of break the verifier can distinguish. */
    public enum Kind {

        /** The entry's own contents no longer hash to its stored hash: it was edited. */
        ENTRY_ALTERED,

        /** The link to the preceding entry is wrong: something was inserted or removed. */
        LINK_BROKEN,

        /** Sequence numbers are not strictly ascending: entries were reordered. */
        OUT_OF_ORDER,

        /** The run does not start at the genesis hash: the head of the history was truncated. */
        NOT_ANCHORED
    }

    public ChainBreak {
        if (kind == null) {
            throw new IllegalArgumentException("a chain break must say what kind it is");
        }
        if (detail == null || detail.isBlank()) {
            throw new IllegalArgumentException("a chain break must explain itself");
        }
    }

    /**
     * A one-line summary suitable for an auditor's report.
     *
     * @return the summary
     */
    public String summary() {
        return "chain break at seq %d (%s): %s".formatted(atSeq, kind, detail);
    }
}
