package zw.ac.qvs.shared;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import zw.ac.qvs.support.PostgresIntegrationTest;
import zw.ac.qvs.testsupport.Requirement;

/**
 * The metrics the dashboard plots, and whether they exist.
 *
 * <p>The failure this exists to prevent is specific and common: a Grafana panel referring to a
 * metric nobody emits. It does not error. It draws an empty chart, indistinguishable from a
 * chart of something that has not happened yet, and an operator glances at a flat green line
 * meaning "no integrity failures" when what it actually means is "this panel has never worked".
 * Monitoring that lies in the reassuring direction is worse than no monitoring.
 *
 * <p>So the dashboard JSON is read, the metric names are pulled out of its queries, and every
 * one of them is required to be present in the scrape this application actually produces. A
 * renamed counter fails the build on the same commit that renames it, rather than being noticed
 * the first time somebody needs the panel.
 */
// Spring Boot switches metrics export off in tests unless a test asks for it, so that a suite
// cannot push junk into a real monitoring backend. Without this annotation there is no
// Prometheus registry to scrape and this class fails for a reason that has nothing to do with
// the application, which is worth knowing before spending an afternoon on it.
@AutoConfigureObservability
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Requirement({"NFR-07", "BONUS-01"})
class MetricsIT extends PostgresIntegrationTest {

    private static final Path DASHBOARD =
            Path.of("..", "ops", "grafana", "dashboards", "qvs-overview.json");

    /** PromQL functions and keywords, which look like metric names and are not. */
    private static final Set<String> PROMQL_WORDS = Set.of(
            "sum", "rate", "increase", "histogram_quantile", "by", "without", "avg", "max",
            "min", "count", "topk", "irate", "delta", "abs", "ceil", "floor", "round",
            "clamp_max", "clamp_min", "label_replace", "on", "ignoring", "group_left",
            "group_right", "offset", "bool", "and", "or", "unless");

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private PrometheusMeterRegistry prometheus;

    @Test
    @DisplayName("every metric the dashboard queries is one this application emits")
    void dashboardQueriesOnlyRealMetrics() throws Exception {
        // Drive a little traffic so the request timers exist. The QVS counters do not need it:
        // they are registered at startup precisely so a panel reads "0" rather than "No data"
        // before the first verification of the day.
        rest.getForEntity("/public/v1/verify/nothing-matches-this", String.class);

        String scrape = prometheus.scrape();
        Set<String> queried = metricNamesIn(Files.readString(DASHBOARD));

        assertThat(queried)
                .as("the dashboard should query something")
                .isNotEmpty();

        for (String metric : queried) {
            assertThat(scrape)
                    .as("dashboard panel queries %s, which this application does not emit — "
                            + "the panel would draw an empty chart and look like good news",
                            metric)
                    .contains(metric);
        }
    }

    @Test
    @DisplayName("the QVS counters exist before anything has happened")
    void counterFamiliesArePreRegistered() {
        String scrape = prometheus.scrape();

        // Registered up front, with every verdict, so an empty register is legible as empty.
        assertThat(scrape).contains("qvs_verifications_total");
        assertThat(scrape).contains("verdict=\"VALID\"");
        assertThat(scrape).contains("verdict=\"REVOKED\"");
        assertThat(scrape).contains("verdict=\"NOT_FOUND\"");
        assertThat(scrape).contains("qvs_verification_reports_total");
        assertThat(scrape).contains("qvs_rate_limit_refusals_total");
        assertThat(scrape).contains("qvs_rate_limit_degraded_total");
    }

    @Test
    @DisplayName("a failed integrity check is counted even though the caller is told NOT_FOUND")
    void integrityFailuresAreVisibleToTheOperator() {
        // The asymmetry this system is built around: the stranger learns nothing about which
        // check failed, the operator learns exactly which one. Both halves matter, and only
        // one of them is testable from the outside — this is the other.
        String scrape = prometheus.scrape();

        assertThat(scrape)
                .as("the failed_check dimension is what the integrity alert fires on")
                .contains("failed_check=");
    }

    @Test
    @DisplayName("the scrape carries no share token, address or personal data")
    void metricsDoNotLeak() {
        rest.getForEntity("/public/v1/verify/a-secret-looking-share-token", String.class);

        // The test's own RestTemplate is instrumented too, and a *client* has no URI template
        // to work from -- it records the literal address it called, token and all. Those
        // `http_client_requests_*` series exist only because this test made the call: the
        // application is not an HTTP client of anything, so the family does not exist in a
        // running deployment. Filtered out here rather than asserted around, so that the day
        // this application does start calling something, the leak is not silently tolerated.
        String scrape = prometheus.scrape().lines()
                .filter(line -> !line.contains("http_client_requests"))
                .collect(Collectors.joining(System.lineSeparator()));

        // Metrics get shipped to a monitoring system, retained for months and read by people
        // who have no business seeing the register. A tag carrying a share token or an address
        // would be a disclosure with a long tail.
        assertThat(scrape).doesNotContain("a-secret-looking-share-token");

        // Spring templates the server-side URI, so the token is a path variable, not a value.
        assertThat(scrape).contains("uri=\"/public/v1/verify/{token}\"");
    }

    /**
     * Pulls metric names out of PromQL, by removing everything that is not one.
     *
     * <p>Label matchers and {@code by (...)} clauses go first, because label names look exactly
     * like metric names once the braces are gone — {@code failed_check} would otherwise be
     * demanded of the scrape as though it were a metric.
     */
    private static Set<String> metricNamesIn(String dashboardJson) {
        Set<String> names = new LinkedHashSet<>();

        Matcher expressions = Pattern.compile("\"expr\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
                .matcher(dashboardJson);

        while (expressions.find()) {
            String expr = expressions.group(1)
                    .replace("\\\"", "\"")
                    .replaceAll("\\{[^}]*}", " ")
                    .replaceAll("\\b(?:by|without)\\s*\\([^)]*\\)", " ");

            Matcher identifiers = Pattern.compile("\\b[a-z][a-z0-9]*(?:_[a-z0-9]+)+\\b")
                    .matcher(expr);
            while (identifiers.find()) {
                String candidate = identifiers.group();
                if (!PROMQL_WORDS.contains(candidate)) {
                    names.add(candidate);
                }
            }
        }
        return names;
    }
}
