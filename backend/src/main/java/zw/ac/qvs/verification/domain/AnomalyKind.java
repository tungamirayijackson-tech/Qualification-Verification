package zw.ac.qvs.verification.domain;

/**
 * The patterns this system knows how to notice.
 *
 * <p>Three, and no more, because each one has to be defensible. A detector that fires on
 * something an ordinary user does is a detector that trains its audience to ignore it, and the
 * cost of that is paid on the day it is right.
 */
public enum AnomalyKind {

    /**
     * One address presenting tokens that match nothing, over and over.
     *
     * <p>A holder who mistypes a link tries it twice and gives up. This is what working through
     * a list looks like. It is not conclusive — a university that emailed four hundred graduates
     * a broken link produces the same shape from four hundred addresses, which is why the rule
     * counts per address rather than in total.
     */
    TOKEN_ENUMERATION(Anomaly.Severity.WARNING),

    /**
     * One address checking many different credentials.
     *
     * <p>Every one of these checks may be legitimate: each needed a token the holder chose to
     * share. What is odd is the concentration. An employer verifying a morning's shortlist looks
     * like this and is fine; so does somebody who has obtained a batch of tokens they were not
     * given, and that is not fine. Worth a human glance, not an automatic refusal.
     */
    CREDENTIAL_HARVESTING(Anomaly.Severity.WARNING),

    /**
     * A credential that failed an integrity check.
     *
     * <p>The only kind here that is not a judgement call. A signature that does not verify, an
     * issuer without standing on the award date, or an issuance missing from the ledger means
     * the presented credential does not match what was signed. The caller was told NOT_FOUND
     * and learned nothing; this is the other half of that decision.
     */
    TAMPERING(Anomaly.Severity.CRITICAL);

    private final Anomaly.Severity severity;

    AnomalyKind(Anomaly.Severity severity) {
        this.severity = severity;
    }

    public Anomaly.Severity severity() {
        return severity;
    }
}
