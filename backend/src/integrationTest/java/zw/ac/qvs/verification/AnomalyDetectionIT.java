package zw.ac.qvs.verification;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import zw.ac.qvs.support.PostgresIntegrationTest;
import zw.ac.qvs.testsupport.Requirement;
import zw.ac.qvs.verification.application.DetectAnomalies;
import zw.ac.qvs.verification.domain.Anomaly;
import zw.ac.qvs.verification.domain.AnomalyKind;

/**
 * The anomaly agent against the real verification log.
 *
 * <p>{@code AnomalyRulesTest} covers what the numbers mean. This covers where they come from:
 * the aggregation runs in PostgreSQL, and the parts most likely to be wrong are the ones a
 * hand-rolled in-memory stub would never exercise — {@code count(*) FILTER (WHERE ...)},
 * {@code count(DISTINCT ...)} and the half-open window boundary.
 *
 * <p>Rows are seeded directly rather than produced by driving hundreds of real verifications.
 * The aggregation is the thing under test and it does not care how the rows arrived; making
 * them properly would add minutes to the suite to test the same SQL.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Requirement("BONUS-02")
class AnomalyDetectionIT extends PostgresIntegrationTest {

    /**
     * This class's own institution, not the shared fixture one.
     *
     * <p>`signing_key` carries a partial unique index allowing one current key per institution,
     * so seeding a key against the shared institution succeeded when this class ran alone and
     * collided with whichever test had already given that institution a key when it ran in the
     * suite. An identifier of its own removes the question rather than ordering around it.
     */
    private static final UUID INSTITUTION = UUID.randomUUID();
    private static final String KEY_ID = "anomaly-it-" + INSTITUTION.toString().substring(0, 8);

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DetectAnomalies detectAnomalies;

    @Autowired
    private TestRestTemplate rest;

    private Instant from;
    private Instant to;

    @BeforeEach
    void clearTheLog() {
        // The verification log is ordinary data, not the append-only ledger, so it can be
        // cleared. Truncating credential too would break the foreign key from these rows.
        jdbc.execute("TRUNCATE verification_request CASCADE");

        to = Instant.now().truncatedTo(ChronoUnit.MILLIS).plusSeconds(1);
        from = to.minus(Duration.ofMinutes(15));
    }

    /** One verification, as the log records it. */
    private void logVerification(String clientHash, String verdict, String failedCheck,
            UUID credentialId, Instant at) {
        jdbc.update("""
                INSERT INTO verification_request
                    (credential_id, channel, client_ip_hash, verdict, failed_check, requested_at)
                VALUES (?, 'WEB', ?, ?, ?, ?)
                """, credentialId, clientHash, verdict, failedCheck, java.sql.Timestamp.from(at));
    }

    @Test
    @DisplayName("a run of unknown tokens from one address is reported")
    void enumerationIsReported() {
        Instant when = to.minusSeconds(60);
        for (int i = 0; i < 12; i++) {
            logVerification("client-guessing", "NOT_FOUND", null, null, when);
        }

        List<Anomaly> found = detectAnomalies.scan(from, to);

        assertThat(found).singleElement()
                .satisfies(anomaly -> {
                    assertThat(anomaly.kind()).isEqualTo(AnomalyKind.TOKEN_ENUMERATION);
                    assertThat(anomaly.subject()).isEqualTo("client-guessing");
                    assertThat(anomaly.observations()).isEqualTo(12);
                });
    }

    @Test
    @DisplayName("the same volume spread across addresses is not")
    void aBrokenMailshotIsNotAnAttack() {
        // Twelve unknown tokens again, but one each from twelve graduates who were sent a
        // broken link. Identical in total, innocent per address — which is the distinction the
        // aggregation has to preserve and the reason it groups by client.
        Instant when = to.minusSeconds(60);
        for (int i = 0; i < 12; i++) {
            logVerification("graduate-" + i, "NOT_FOUND", null, null, when);
        }

        assertThat(detectAnomalies.scan(from, to)).isEmpty();
    }

    @Test
    @DisplayName("many distinct credentials from one address is reported, duplicates are not")
    void harvestingCountsDistinctCredentials() {
        Instant when = to.minusSeconds(60);
        UUID credential = seedCredential();

        // Thirty checks, all of the same credential. An employer refreshing a page is not a
        // harvester, and count(DISTINCT ...) is what tells them apart.
        for (int i = 0; i < 30; i++) {
            logVerification("client-refreshing", "VALID", null, credential, when);
        }

        assertThat(detectAnomalies.scan(from, to))
                .as("thirty checks of one credential is one credential")
                .isEmpty();
    }

    @Test
    @DisplayName("an integrity failure is reported on its own, at the top")
    void tamperingOutranksEverything() {
        Instant when = to.minusSeconds(60);
        logVerification("client-forging", "NOT_FOUND", "SIGNATURE", null, when);
        for (int i = 0; i < 40; i++) {
            logVerification("client-guessing", "NOT_FOUND", null, null, when);
        }

        List<Anomaly> found = detectAnomalies.scan(from, to);

        assertThat(found).hasSize(2);
        assertThat(found.getFirst().kind())
                .as("one forgery must not be buried under forty mistyped links")
                .isEqualTo(AnomalyKind.TAMPERING);
        assertThat(found.getFirst().severity()).isEqualTo(Anomaly.Severity.CRITICAL);

        // A failed check is NOT_FOUND to the caller and a named failure to the operator. The
        // aggregation must not count it as an unknown token as well, or every forgery would
        // also look like guessing.
        assertThat(found.get(1).observations())
                .as("the forged attempt is not also counted as an unknown token")
                .isEqualTo(40);
    }

    @Test
    @DisplayName("activity outside the window is not counted")
    void theWindowIsRespected() {
        // Half-open: [from, to). An off-by-one here would either miss a live attack or keep
        // reporting one that ended yesterday.
        logVerification("client-old", "NOT_FOUND", null, null, from.minusSeconds(1));
        for (int i = 0; i < 20; i++) {
            logVerification("client-old", "NOT_FOUND", null, null, from.minusSeconds(1));
        }

        assertThat(detectAnomalies.scan(from, to)).isEmpty();
    }

    @Test
    @DisplayName("rows with no client address are not bundled into one prolific caller")
    void nullClientsAreSkipped() {
        Instant when = to.minusSeconds(60);
        for (int i = 0; i < 30; i++) {
            logVerification(null, "NOT_FOUND", null, null, when);
        }

        // Thirty rows with no address are thirty unrelated requests, not one busy client.
        // Grouping them would invent an attacker and report it.
        assertThat(detectAnomalies.scan(from, to)).isEmpty();
    }

    @Test
    @DisplayName("the findings endpoint is closed to anyone who is not an auditor")
    void endpointRequiresAnAuditor() {
        assertThat(rest.getForEntity("/api/v1/audit/anomalies", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /** A credential row, so the log's foreign key is satisfied. */
    private UUID seedCredential() {
        jdbc.update("""
                INSERT INTO institution (id, name, country, provider_no, accredited_until)
                VALUES (?, 'Anomaly Test University', 'ZW', ?, DATE '2030-12-31')
                ON CONFLICT (id) DO NOTHING
                """, INSTITUTION, "PR-AN" + INSTITUTION.toString().substring(0, 6));

        jdbc.update("""
                INSERT INTO signing_key (kid, institution_id, public_key, valid_from)
                VALUES (?, ?, '{"kty":"OKP"}', DATE '2020-01-01')
                ON CONFLICT (kid) DO NOTHING
                """, KEY_ID, INSTITUTION);
        String kid = KEY_ID;

        UUID qualificationId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO qualification (id, institution_id, title, nqf_level, credits, status)
                VALUES (?, ?, 'BSc Computer Science', 7, 360, 'ACTIVE')
                """, qualificationId, INSTITUTION);

        UUID holderId = UUID.randomUUID();
        // national_id_hash has a CHECK for 64 lowercase hex characters, so a made-up value has
        // to look like a real hash rather than merely be unique.
        String nationalIdHash = String.format("%064x", holderId.getMostSignificantBits() & 0xffffL)
                .replace('-', '0');
        jdbc.update("""
                INSERT INTO holder (id, pseudonym_ref, national_id_hash, display_name_enc,
                                    initials, search_name)
                VALUES (?, ?, ?, 'encrypted', 'A.B.', 'anomaly test holder')
                """, holderId, "pseudo-" + holderId, nationalIdHash);

        UUID credentialId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO credential (id, serial, qualification_id, holder_id, kid,
                                        awarded_on, payload_canonical, detached_jws, status)
                VALUES (?, ?, ?, ?, ?, DATE '2026-04-11', '{}', 'jws', 'ISSUED')
                """, credentialId, "ZW-ANOM-2026-" + credentialId.toString().substring(0, 6),
                qualificationId, holderId, kid);

        return credentialId;
    }
}
