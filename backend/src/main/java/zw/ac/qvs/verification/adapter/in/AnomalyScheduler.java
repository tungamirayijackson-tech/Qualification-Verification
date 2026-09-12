package zw.ac.qvs.verification.adapter.in;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import zw.ac.qvs.verification.application.DetectAnomalies;
import zw.ac.qvs.verification.domain.Anomaly;
import zw.ac.qvs.verification.domain.AnomalyKind;

/**
 * Runs the anomaly scan on a timer and reports what it finds.
 *
 * <p>Reports to two places, for two audiences. The log carries the detail — the counts, the
 * window, the sentence a person can act on — for whoever is reading logs when something goes
 * wrong. The counter carries only the shape, for the dashboard and the alert rules, because a
 * metric label is a low-cardinality thing and a hashed client address is not: one series per
 * address would eventually mean one series per visitor, which is how a monitoring system is
 * brought down by its own instrumentation.
 *
 * <p>It does not act. See {@link DetectAnomalies} for why that is a decision rather than an
 * omission.
 */
public class AnomalyScheduler {

    private static final Logger log = LoggerFactory.getLogger(AnomalyScheduler.class);

    private final DetectAnomalies detectAnomalies;
    private final Duration window;
    private final MeterRegistry meters;
    private final ConcurrentMap<AnomalyKind, Counter> counters = new ConcurrentHashMap<>();

    public AnomalyScheduler(
            DetectAnomalies detectAnomalies, Duration window, MeterRegistry meters) {
        this.detectAnomalies = detectAnomalies;
        this.window = window;
        this.meters = meters;

        // Every kind registered at startup, so a dashboard panel reads 0 rather than "No data"
        // on a quiet register — which would be indistinguishable from a panel that is broken.
        for (AnomalyKind kind : AnomalyKind.values()) {
            counterFor(kind);
        }
    }

    /**
     * Scans the recent window.
     *
     * <p>Fixed delay rather than fixed rate: if a scan ever takes longer than the interval, the
     * next one starts after it finishes instead of piling up behind it. A monitoring job that
     * can saturate the database it is monitoring has become the incident.
     */
    @Scheduled(fixedDelayString = "${qvs.anomaly.scan-interval:PT5M}", initialDelay = 60_000)
    public void scan() {
        try {
            List<Anomaly> found = detectAnomalies.scan(window);
            if (found.isEmpty()) {
                log.debug("anomaly scan over the last {}: nothing unusual", window);
                return;
            }

            for (Anomaly anomaly : found) {
                counterFor(anomaly.kind()).increment();
                log.warn("anomaly [{}] {} — {} ({} observations between {} and {})",
                        anomaly.severity(), anomaly.kind(), anomaly.detail(),
                        anomaly.observations(), anomaly.from(), anomaly.to());
            }
        } catch (RuntimeException e) {
            // A failing scan must not kill the schedule. Spring stops re-running a scheduled
            // method that throws, so an unhandled exception here would silence the agent
            // permanently and quietly — the worst way for a watchdog to stop working.
            log.error("anomaly scan failed; the schedule continues", e);
        }
    }

    private Counter counterFor(AnomalyKind kind) {
        return counters.computeIfAbsent(kind, key -> Counter.builder("qvs.anomalies")
                .description("Anomalies reported by the detection agent")
                .tag("kind", key.name())
                .tag("severity", key.severity().name())
                .register(meters));
    }
}
