package zw.ac.qvs.verification.adapter.in;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;
import zw.ac.qvs.verification.application.VerifyCredential;
import zw.ac.qvs.verification.domain.Verdict;

/**
 * What the operator sees, counted at the edge.
 *
 * <p>Telemetry lives in the adapter ring, not in the use case. {@code VerifyCredential} decides
 * what is true about a credential; how that gets reported to a monitoring system is a fact about
 * this deployment, and threading a metrics library through the application layer would make
 * every use case depend on the observability stack of the day.
 *
 * <p><b>The failed check is counted but never returned.</b> A public verification that fails on
 * its signature reports {@code NOT_FOUND} to the caller — deliberately, so an attacker cannot
 * learn which of their forgeries came closest. The same event increments
 * {@code qvs_verifications_total{verdict="NOT_FOUND",failed_check="SIGNATURE"}} here, where the
 * people whose job it is to act on tampering can see it. That asymmetry is the whole point:
 * the information is not suppressed, it is routed away from the person who may have caused it.
 *
 * <p>Counters are cached rather than resolved on every request. {@code Counter.builder} does a
 * map lookup and builds tag lists each time, and this sits on the one path a stranger can drive
 * as fast as the rate limiter allows.
 */
@Component
public class VerificationMetrics {

    /** One name, tagged — so a dashboard can sum over verdicts or break them out. */
    private static final String VERIFICATIONS = "qvs.verifications";
    private static final String REPORTS = "qvs.verification.reports";

    /** The tag value used when a verdict has no failing check, so the tag is never absent. */
    private static final String NONE = "none";

    private final MeterRegistry registry;
    private final ConcurrentMap<String, Counter> counters = new ConcurrentHashMap<>();

    public VerificationMetrics(MeterRegistry registry) {
        this.registry = registry;

        // Registered up front so a dashboard panel reads "0" rather than "No data" before the
        // first verification of the day. A panel that cannot tell "nothing happened" from
        // "the metric does not exist" is a panel that gets ignored.
        for (String verdict : new String[] {"VALID", "REVOKED", "NOT_FOUND"}) {
            verificationCounter(verdict, NONE);
        }
        reportCounter();
    }

    /**
     * Records the outcome of one public verification.
     *
     * @param outcome what the check found
     */
    public void verificationCompleted(VerifyCredential.Outcome outcome) {
        Verdict verdict = outcome.verdict();
        String failedCheck = verdict instanceof Verdict.NotFound notFound
                && notFound.failedCheck() != null
                ? notFound.failedCheck().name()
                : NONE;

        verificationCounter(verdict.name(), failedCheck).increment();
    }

    /** Records that a verifier took a signed report away with them (FR-12). */
    public void reportProduced() {
        reportCounter().increment();
    }

    private Counter verificationCounter(String verdict, String failedCheck) {
        return counters.computeIfAbsent(verdict + '|' + failedCheck, key ->
                Counter.builder(VERIFICATIONS)
                        .description("Public credential verifications, by what they found")
                        .tag("verdict", verdict)
                        .tag("failed_check", failedCheck)
                        .register(registry));
    }

    private Counter reportCounter() {
        return counters.computeIfAbsent("report", key ->
                Counter.builder(REPORTS)
                        .description("Signed verification reports produced")
                        .register(registry));
    }
}
