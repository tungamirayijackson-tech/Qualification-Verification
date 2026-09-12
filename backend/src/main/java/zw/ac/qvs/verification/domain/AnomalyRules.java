package zw.ac.qvs.verification.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The detection itself: pure functions over counts, with the thresholds passed in.
 *
 * <p>Plain Java, no framework, no database. That is what lets the interesting question — what
 * counts as suspicious — be answered in a unit test with a handful of numbers, rather than by
 * seeding a database and hoping. The queries that produce those numbers are somebody else's
 * job; this class decides what they mean.
 *
 * <p>Thresholds are parameters rather than constants because the right value is a property of
 * the deployment, not of the algorithm. A national register serving thousands of employers a day
 * and a single university's demo do not share a definition of "a lot of checks from one place",
 * and hard-coding either would make the class wrong somewhere.
 */
public final class AnomalyRules {

    private AnomalyRules() {
    }

    /**
     * What one client address did in the window.
     *
     * @param clientHash          the salted hash of the address; never a raw address
     * @param unknownTokens       checks that matched nothing at all
     * @param distinctCredentials how many different credentials were successfully checked
     * @param total               every check from this address in the window
     */
    public record ClientActivity(
            String clientHash, long unknownTokens, long distinctCredentials, long total) {
    }

    /**
     * How many credentials failed a given integrity check in the window.
     *
     * @param failedCheck which conjunct failed
     * @param occurrences how many times
     */
    public record IntegrityFailures(String failedCheck, long occurrences) {
    }

    /**
     * The thresholds a deployment considers unusual.
     *
     * @param unknownTokensPerClient    unknown-token checks from one address before it is odd
     * @param distinctCredentialsPerClient different credentials from one address before it is odd
     */
    public record Thresholds(long unknownTokensPerClient, long distinctCredentialsPerClient) {

        public Thresholds {
            if (unknownTokensPerClient < 1 || distinctCredentialsPerClient < 1) {
                // A threshold of zero fires on the first legitimate request and would make the
                // whole feature noise. Refused loudly rather than silently clamped, because a
                // zero here almost certainly means a misread configuration file.
                throw new IllegalArgumentException(
                        "thresholds must be at least 1, got " + unknownTokensPerClient
                                + " and " + distinctCredentialsPerClient);
            }
        }
    }

    /**
     * Examines a window's activity and reports what stands out.
     *
     * @param clients    per-address activity in the window
     * @param integrity  integrity failures in the window
     * @param thresholds what this deployment considers unusual
     * @param from       start of the window
     * @param to         end of the window
     * @return findings, most serious first
     */
    public static List<Anomaly> examine(
            List<ClientActivity> clients,
            List<IntegrityFailures> integrity,
            Thresholds thresholds,
            Instant from,
            Instant to) {

        List<Anomaly> found = new ArrayList<>();

        for (IntegrityFailures failure : integrity) {
            if (failure.occurrences() > 0) {
                // No threshold. One credential that does not match what was signed is worth a
                // person's attention, and a rule that waited for a second one would be saying
                // the first was acceptable.
                found.add(new Anomaly(AnomalyKind.TAMPERING, failure.failedCheck(),
                        failure.occurrences(), 0, from, to,
                        failure.occurrences() + " verification(s) failed the "
                                + failure.failedCheck() + " check. The callers were told "
                                + "NOT_FOUND. The serials are in the audit ledger."));
            }
        }

        for (ClientActivity client : clients) {
            if (client.unknownTokens() >= thresholds.unknownTokensPerClient()) {
                found.add(new Anomaly(AnomalyKind.TOKEN_ENUMERATION, client.clientHash(),
                        client.unknownTokens(), 0, from, to,
                        client.unknownTokens() + " of " + client.total() + " checks from this "
                                + "address matched no credential. Someone who mistypes a link "
                                + "tries it twice."));
            }

            if (client.distinctCredentials() >= thresholds.distinctCredentialsPerClient()) {
                found.add(new Anomaly(AnomalyKind.CREDENTIAL_HARVESTING, client.clientHash(),
                        client.total(), client.distinctCredentials(), from, to,
                        client.distinctCredentials() + " different credentials were checked "
                                + "from this address. Each needed a token a holder shared, so "
                                + "this may be an employer working through a shortlist."));
            }
        }

        // Most serious first, then largest, so a reader who stops after three lines has read
        // the three that mattered.
        found.sort(Comparator
                .comparing((Anomaly a) -> a.severity().ordinal()).reversed()
                .thenComparing(Comparator.comparingLong(Anomaly::observations).reversed()));
        return List.copyOf(found);
    }
}
