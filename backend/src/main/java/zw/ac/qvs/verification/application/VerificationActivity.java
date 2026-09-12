package zw.ac.qvs.verification.application;

import java.time.Instant;
import java.util.List;
import zw.ac.qvs.verification.domain.AnomalyRules;

/**
 * A read model over the verification log, aggregated for the anomaly scan.
 *
 * <p>Aggregated in the database on purpose. The alternative — reading every verification in the
 * window and counting in Java — is the version that works fine on a demo and falls over on a
 * register with a busy afternoon in it. The counting is what a database is for.
 */
public interface VerificationActivity {

    /**
     * What each client address did in a window.
     *
     * @param from window start, inclusive
     * @param to   window end, exclusive
     * @return one row per address that made at least one check
     */
    List<AnomalyRules.ClientActivity> byClient(Instant from, Instant to);

    /**
     * Integrity failures in a window, grouped by which check failed.
     *
     * @param from window start, inclusive
     * @param to   window end, exclusive
     * @return one row per failing check that occurred
     */
    List<AnomalyRules.IntegrityFailures> integrityFailures(Instant from, Instant to);
}
