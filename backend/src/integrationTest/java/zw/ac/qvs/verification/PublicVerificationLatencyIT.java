package zw.ac.qvs.verification;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import zw.ac.qvs.credential.application.AtomicRegistration;
import zw.ac.qvs.credential.application.RegisterCredential;
import zw.ac.qvs.credential.domain.Credential;
import zw.ac.qvs.support.PostgresIntegrationTest;
import zw.ac.qvs.testsupport.Requirement;
import zw.ac.qvs.verification.application.IssueShareToken;
import zw.ac.qvs.verification.application.KeyVault;

/**
 * NFR-01, as a regression guard rather than as the measurement.
 *
 * <p><b>The number this requirement is judged on comes from {@code perf/verify-smoke.js}</b>,
 * run against the Compose stack: p95 111 ms at 50 concurrent users, against a 400 ms target.
 * This test deliberately does not assert that figure. It shares a JVM, a Docker host and a
 * PostgreSQL container with the rest of the integration suite, and a 400 ms wall-clock
 * assertion in that setting would measure the machine's mood as much as the code — the same
 * trap FR-02's import timing fell into, where one run took 30 seconds alone and 51 under
 * contention.
 *
 * <p>What it does instead is run enough concurrent verifications to catch the failures that
 * matter and are not subtle: an N+1 query, a dropped index, a lock taken on the verify path, a
 * ledger append that starts serialising every request behind one another. Those do not move a
 * p95 from 111 ms to 450 ms; they move it to seconds. So the ceiling is generous and the
 * measured figure is printed, which keeps the test honest about what it is: a tripwire, not a
 * benchmark.
 *
 * <p>Every virtual client presents its own address, for the reason set out in the k6 script —
 * the endpoint allows 60 requests a minute per address, and a test that spent its time
 * collecting 429s would report a wonderful latency for refusing to do any work.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Requirement("NFR-01")
class PublicVerificationLatencyIT extends PostgresIntegrationTest {

    private static final UUID INSTITUTION = UUID.fromString("11111111-1111-4111-8111-111111111111");

    private static final int CLIENTS = 20;
    private static final int REQUESTS_PER_CLIENT = 15;

    /**
     * A tripwire, not the requirement, and set from measurement rather than from taste.
     *
     * <p>Three consecutive runs of this test on a developer workstation produced p95 figures of
     * <b>1101, 1951 and 1586 ms</b> — nearly a twofold spread for identical code, which is what
     * twenty unpaced clients against a Testcontainers PostgreSQL sharing a JVM with the server
     * looks like. The first ceiling tried here was 1500 ms; it would have failed two of those
     * three runs. A threshold that close to the noise floor is not a tripwire, it is a
     * generator of red builds nobody trusts.
     *
     * <p>Five seconds sits at roughly two and a half times the worst figure observed. Being
     * plain about what that buys: this catches a regression that <em>serialises</em> the verify
     * path — a lock, a chain append that starts blocking, a transaction held across a network
     * call — because those push a p95 into tens of seconds. It will not catch an extra query
     * costing thirty milliseconds. Nothing gated by a wall clock on shared infrastructure
     * could, and pretending otherwise would be worse than saying so.
     */
    private static final Duration REGRESSION_CEILING = Duration.ofMillis(5_000);

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private KeyVault keyVault;

    @Autowired
    private AtomicRegistration registration;

    @Autowired
    private IssueShareToken issueShareToken;

    @Autowired
    private TransactionTemplate transactions;

    private String shareToken;

    @BeforeEach
    void registerAndShare() {
        jdbc.execute("TRUNCATE credential, holder, qualification, app_user, serial_counter "
                + "CASCADE");

        jdbc.update("""
                INSERT INTO institution (id, name, country, provider_no, accredited_until)
                VALUES (?, 'Example University', 'ZW', 'PR-0142', DATE '2030-12-31')
                ON CONFLICT (id) DO NOTHING
                """, INSTITUTION);

        var key = keyVault.currentKeyFor(INSTITUTION)
                .orElseGet(() -> keyVault.rotate(INSTITUTION, LocalDate.of(2020, 1, 1)));
        jdbc.update("UPDATE institution SET active_key_id = ? WHERE id = ?",
                key.kid(), INSTITUTION);

        UUID qualificationId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO qualification (id, institution_id, title, nqf_level, credits, status)
                VALUES (?, ?, 'BSc Computer Science', 7, 360, 'ACTIVE')
                """, qualificationId, INSTITUTION);

        Credential credential = registration.registerInOwnTransaction(
                new RegisterCredential.Command(INSTITUTION, qualificationId, "44-4567890R31",
                        "Zwelibanzi Q. Ntshangase", LocalDate.of(1991, 5, 12),
                        LocalDate.of(2026, 4, 11), null));

        shareToken = transactions.execute(status ->
                issueShareToken.issue(credential.serial().value(), 30, "latency test", null, null))
                .secret();
    }

    @Test
    @DisplayName("NFR-01: concurrent public verifications stay well inside the regression ceiling")
    void concurrentVerificationsStayFast() throws Exception {
        AtomicInteger refused = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        List<Long> durations;

        try (ExecutorService pool = Executors.newFixedThreadPool(CLIENTS)) {
            List<Callable<List<Long>>> clients = new ArrayList<>(CLIENTS);
            for (int client = 0; client < CLIENTS; client++) {
                clients.add(virtualClient(client, refused, failed));
            }

            List<Future<List<Long>>> results = pool.invokeAll(clients, 4, TimeUnit.MINUTES);
            durations = new ArrayList<>(CLIENTS * REQUESTS_PER_CLIENT);
            for (Future<List<Long>> result : results) {
                durations.addAll(result.get());
            }
        }

        assertThat(durations).hasSize(CLIENTS * REQUESTS_PER_CLIENT);

        // A run polluted by refusals or errors is not a measurement of this requirement, so
        // these are asserted before the timings rather than alongside them.
        assertThat(refused.get())
                .as("a 429 means the pacing is wrong and the timings measure the wrong thing")
                .isZero();
        assertThat(failed.get()).as("every verification answered 200 with a VALID verdict")
                .isZero();

        durations.sort(Comparator.naturalOrder());
        long p50 = percentile(durations, 50);
        long p95 = percentile(durations, 95);
        long p99 = percentile(durations, 99);

        System.out.printf(
                "NFR-01 tripwire: %d verifications from %d concurrent clients — "
                        + "p50 %d ms, p95 %d ms, p99 %d ms, max %d ms "
                        + "(the requirement's own figure comes from perf/verify-smoke.js)%n",
                durations.size(), CLIENTS, p50, p95, p99, durations.getLast());

        assertThat(p95)
                .as("p95 of %d verifications; the ceiling is a tripwire, not the target",
                        durations.size())
                .isLessThan(REGRESSION_CEILING.toMillis());
    }

    /** One virtual client: its own address, its own budget, its own timings. */
    private Callable<List<Long>> virtualClient(
            int index, AtomicInteger refused, AtomicInteger failed) {

        return () -> {
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-Forwarded-For", "10.0." + index + ".1");
            HttpEntity<Void> request = new HttpEntity<>(headers);

            List<Long> timings = new ArrayList<>(REQUESTS_PER_CLIENT);
            for (int i = 0; i < REQUESTS_PER_CLIENT; i++) {
                long startedAt = System.nanoTime();
                ResponseEntity<String> response = rest.exchange(
                        "/public/v1/verify/" + shareToken, HttpMethod.GET, request, String.class);
                timings.add((System.nanoTime() - startedAt) / 1_000_000);

                if (response.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                    refused.incrementAndGet();
                } else if (response.getStatusCode() != HttpStatus.OK
                        || response.getBody() == null
                        || !response.getBody().contains("\"verdict\":\"VALID\"")) {
                    failed.incrementAndGet();
                }
            }
            return timings;
        };
    }

    /** Nearest-rank percentile over an already sorted list. */
    private static long percentile(List<Long> sorted, int percentile) {
        int rank = (int) Math.ceil(percentile / 100.0 * sorted.size());
        return sorted.get(Math.min(Math.max(rank - 1, 0), sorted.size() - 1));
    }
}
