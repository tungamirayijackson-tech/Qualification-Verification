package zw.ac.qvs.ledger.domain;

import java.time.Instant;
import java.util.Optional;

/**
 * The answer an auditor gets when they ask whether the history is intact.
 *
 * @param verifiedAt   when the check ran
 * @param entriesRead  how many entries were recomputed
 * @param headSequence the sequence number at the head of the chain
 * @param firstBreak   the first break found, or null when the chain is intact
 */
public record ChainVerification(
        Instant verifiedAt, long entriesRead, long headSequence, ChainBreak firstBreak) {

    /**
     * Whether the chain recomputed end to end.
     *
     * @return true when nothing was found wrong
     */
    public boolean isIntact() {
        return firstBreak == null;
    }

    /**
     * The break, if any.
     *
     * @return the first break
     */
    public Optional<ChainBreak> brokenAt() {
        return Optional.ofNullable(firstBreak);
    }

    /**
     * A sentence an auditor can put in a report.
     *
     * @return the summary
     */
    public String summary() {
        if (isIntact()) {
            return "chain intact: %d entries verified to seq %d".formatted(entriesRead, headSequence);
        }
        return "history is trustworthy up to seq %d; %s"
                .formatted(firstBreak.atSeq() - 1, firstBreak.summary());
    }
}
