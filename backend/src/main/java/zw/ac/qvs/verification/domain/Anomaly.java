package zw.ac.qvs.verification.domain;

import java.time.Instant;

/**
 * Something in the verification traffic that a person should look at.
 *
 * <p>Deliberately not called an alert or an incident. This system cannot tell a token-guessing
 * script from a university that emailed a broken link to four hundred graduates, and saying
 * otherwise in the type name would encourage callers to treat a finding as a verdict. What it
 * can do is notice the shape of the traffic and say what it saw, with the numbers, so somebody
 * who knows what was happening that afternoon can decide.
 *
 * <p>The subject is a <b>hashed</b> client address, never a raw one — the same salted hash the
 * verification log stores. That is enough to say "these two hundred checks came from one place"
 * and not enough to say where. An anomaly report that deanonymised the people it described
 * would be a worse privacy problem than the one it was written to catch.
 *
 * @param kind        what pattern was seen
 * @param subject     the hashed client address it was seen from, or a check name for tampering
 * @param observations how many events made up the finding
 * @param distinctCredentials how many different credentials were touched, where that is the point
 * @param from        start of the window examined
 * @param to          end of the window examined
 * @param detail      a sentence a person can act on
 */
public record Anomaly(
        AnomalyKind kind,
        String subject,
        long observations,
        long distinctCredentials,
        Instant from,
        Instant to,
        String detail) {

    public Anomaly {
        if (kind == null) {
            throw new IllegalArgumentException("an anomaly must say what kind it is");
        }
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("an anomaly must say what it is about");
        }
        if (from == null || to == null || to.isBefore(from)) {
            throw new IllegalArgumentException("an anomaly must cover a real window of time");
        }
    }

    /**
     * How much attention this deserves, which is not the same as how many events it counts.
     *
     * <p>One altered credential outranks a thousand mistyped links. The severity is a property
     * of the kind rather than of the volume, because volume is what an attacker controls.
     *
     * @return the severity
     */
    public Severity severity() {
        return kind.severity();
    }

    /** Ordered, so a caller can sort findings by how much they matter. */
    public enum Severity {
        INFO, WARNING, CRITICAL
    }
}
