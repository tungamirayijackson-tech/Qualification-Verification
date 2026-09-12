package zw.ac.qvs.verification.adapter.in;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Duration;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import zw.ac.qvs.verification.application.DetectAnomalies;
import zw.ac.qvs.verification.domain.Anomaly;

/**
 * What the anomaly agent has noticed, for an auditor.
 *
 * <p>Served under {@code /api/v1/audit} deliberately. The path already carries an
 * AUDITOR-only rule in the authorisation policy, so this endpoint inherits it rather than
 * introducing a second rule that could drift from the first — the kind of drift that left
 * {@code /sign-in} answering 401 for a fortnight. The {@code @PreAuthorize} below restates it,
 * as every endpoint in this system does, so the rule survives the URL being remapped.
 *
 * <p>Findings are computed when asked for, not read from a table of stored conclusions. They
 * are a view of the verification log, and the log is already the record; a second table of
 * conclusions drawn from it would be a copy that could disagree with its source.
 */
@RestController
@RequestMapping("/api/v1/audit")
@Tag(name = "Anomaly detection",
        description = "Patterns in the verification traffic that a person should look at")
public class AnomalyController {

    /** A day. Long enough to be useful, short enough that the query stays a query. */
    private static final int MAX_WINDOW_MINUTES = 1_440;

    private final DetectAnomalies detectAnomalies;

    public AnomalyController(DetectAnomalies detectAnomalies) {
        this.detectAnomalies = detectAnomalies;
    }

    /**
     * One finding, as an auditor sees it.
     *
     * @param kind                what pattern was seen
     * @param severity            how much attention it deserves
     * @param subject             the hashed client address, or the name of the failing check
     * @param observations        how many events made up the finding
     * @param distinctCredentials how many different credentials were touched
     * @param from                start of the window examined
     * @param to                  end of the window examined
     * @param detail              a sentence a person can act on
     */
    public record AnomalyView(
            String kind,
            String severity,
            String subject,
            long observations,
            long distinctCredentials,
            String from,
            String to,
            String detail) {

        static AnomalyView of(Anomaly anomaly) {
            return new AnomalyView(
                    anomaly.kind().name(),
                    anomaly.severity().name(),
                    anomaly.subject(),
                    anomaly.observations(),
                    anomaly.distinctCredentials(),
                    anomaly.from().toString(),
                    anomaly.to().toString(),
                    anomaly.detail());
        }
    }

    /**
     * Scans recent verification traffic.
     *
     * @param windowMinutes how far back to look
     * @return findings, most serious first
     */
    @GetMapping("/anomalies")
    @PreAuthorize("hasRole('AUDITOR')")
    @Operation(summary = "Patterns in recent verification traffic",
            description = "Reports token enumeration, credential harvesting and integrity "
                    + "failures. Subjects are salted hashes of client addresses, never "
                    + "addresses: enough to say two hundred checks came from one place, not "
                    + "enough to say where. The agent reports and never acts.")
    public List<AnomalyView> anomalies(
            @RequestParam(defaultValue = "60") int windowMinutes) {

        if (windowMinutes < 1 || windowMinutes > MAX_WINDOW_MINUTES) {
            throw new IllegalArgumentException(
                    "windowMinutes must be between 1 and " + MAX_WINDOW_MINUTES);
        }

        return detectAnomalies.scan(Duration.ofMinutes(windowMinutes)).stream()
                .map(AnomalyView::of)
                .toList();
    }
}
