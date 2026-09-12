package zw.ac.qvs.verification.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import zw.ac.qvs.verification.domain.Anomaly;
import zw.ac.qvs.verification.domain.AnomalyRules;

/**
 * The anomaly agent: looks at a window of verification traffic and says what stands out.
 *
 * <p>It <b>reports and does not act</b>. No address is blocked, no credential is withdrawn, no
 * account is locked. That is a deliberate limit rather than an unfinished feature. Every pattern
 * this notices has an innocent explanation that is more likely than the guilty one — an employer
 * checking a shortlist looks exactly like somebody working through stolen tokens, and a
 * university that emailed four hundred graduates a broken link produces a wall of unknown-token
 * checks. A system that acted on those would deny service to the people it exists to serve, and
 * would do it automatically, at scale, on a Friday afternoon.
 *
 * <p>The one finding that is not a judgement call — a credential failing an integrity check — is
 * also the one where acting automatically would be worst: the right response is a person reading
 * the audit ledger, not a machine deciding whose qualification to distrust.
 *
 * <p>Findings are computed on demand rather than stored. They are a view of the verification
 * log, and the log is already append-only and already the record; a second table of conclusions
 * drawn from it would be a copy that could disagree with its source.
 */
public class DetectAnomalies {

    private final VerificationActivity activity;
    private final AnomalyRules.Thresholds thresholds;
    private final Clock clock;

    public DetectAnomalies(
            VerificationActivity activity, AnomalyRules.Thresholds thresholds, Clock clock) {
        this.activity = activity;
        this.thresholds = thresholds;
        this.clock = clock;
    }

    /**
     * Scans the most recent window.
     *
     * @param window how far back to look
     * @return findings, most serious first
     */
    public List<Anomaly> scan(Duration window) {
        if (window == null || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("a scan needs a window with time in it");
        }
        Instant to = clock.instant();
        return scan(to.minus(window), to);
    }

    /**
     * Scans an explicit window, which is what makes this testable against fixed data.
     *
     * @param from window start, inclusive
     * @param to   window end, exclusive
     * @return findings, most serious first
     */
    public List<Anomaly> scan(Instant from, Instant to) {
        return AnomalyRules.examine(
                activity.byClient(from, to),
                activity.integrityFailures(from, to),
                thresholds,
                from,
                to);
    }
}
