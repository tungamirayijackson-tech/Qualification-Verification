package zw.ac.qvs.verification.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import zw.ac.qvs.testsupport.Requirement;

/**
 * What counts as suspicious.
 *
 * <p>These are the assertions worth arguing about, and they are all here in plain Java with no
 * database, because the rules are plain functions over counts. The queries that produce the
 * counts are tested separately; this is about what the numbers mean.
 *
 * <p>Several of these tests exist to pin down what the agent must <em>not</em> report. A
 * detector that fires on ordinary behaviour trains its audience to ignore it, and that cost is
 * paid on the day it is right.
 */
@Requirement("BONUS-02")
class AnomalyRulesTest {

    private static final Instant FROM = Instant.parse("2026-09-07T09:00:00Z");
    private static final Instant TO = Instant.parse("2026-09-07T09:15:00Z");

    private static final AnomalyRules.Thresholds THRESHOLDS =
            new AnomalyRules.Thresholds(10, 25);

    private static List<Anomaly> examine(
            List<AnomalyRules.ClientActivity> clients,
            List<AnomalyRules.IntegrityFailures> integrity) {
        return AnomalyRules.examine(clients, integrity, THRESHOLDS, FROM, TO);
    }

    @Nested
    @DisplayName("tampering")
    class Tampering {

        @Test
        @DisplayName("is reported on the very first occurrence")
        void reportedImmediately() {
            // No threshold, deliberately. A rule that waited for a second altered credential
            // would be saying the first one was acceptable.
            List<Anomaly> found = examine(List.of(),
                    List.of(new AnomalyRules.IntegrityFailures("SIGNATURE", 1)));

            assertThat(found).hasSize(1);
            assertThat(found.getFirst().kind()).isEqualTo(AnomalyKind.TAMPERING);
            assertThat(found.getFirst().severity()).isEqualTo(Anomaly.Severity.CRITICAL);
            assertThat(found.getFirst().subject()).isEqualTo("SIGNATURE");
        }

        @Test
        @DisplayName("outranks a larger pile of unknown tokens")
        void outranksVolume() {
            // Severity is a property of the kind, not of the count, because the count is what
            // an attacker controls. Nine hundred mistyped links must not bury one forgery.
            List<Anomaly> found = examine(
                    List.of(new AnomalyRules.ClientActivity("client-a", 900, 0, 900)),
                    List.of(new AnomalyRules.IntegrityFailures("SIGNATURE", 1)));

            assertThat(found.getFirst().kind()).isEqualTo(AnomalyKind.TAMPERING);
            assertThat(found).hasSize(2);
        }

        @Test
        @DisplayName("names the check that failed, so the auditor knows where to look")
        void namesTheCheck() {
            List<Anomaly> found = examine(List.of(),
                    List.of(new AnomalyRules.IntegrityFailures("LEDGER_PRESENCE", 3)));

            assertThat(found.getFirst().detail())
                    .contains("LEDGER_PRESENCE")
                    .contains("3")
                    .contains("audit ledger");
        }
    }

    @Nested
    @DisplayName("token enumeration")
    class Enumeration {

        @Test
        @DisplayName("is reported at the threshold, not above it")
        void firesAtTheThreshold() {
            List<Anomaly> found = examine(
                    List.of(new AnomalyRules.ClientActivity("client-a", 10, 0, 10)), List.of());

            assertThat(found).singleElement()
                    .extracting(Anomaly::kind).isEqualTo(AnomalyKind.TOKEN_ENUMERATION);
        }

        @Test
        @DisplayName("is not reported for somebody who mistyped a link twice")
        void doesNotFireOnOrdinaryMistakes() {
            // The case this rule most needs to get right. A holder fumbling a link is the
            // overwhelmingly common cause of an unknown token.
            List<Anomaly> found = examine(
                    List.of(new AnomalyRules.ClientActivity("client-a", 2, 1, 3)), List.of());

            assertThat(found).isEmpty();
        }

        @Test
        @DisplayName("counts per address, so a broken mailshot does not look like an attack")
        void countsPerAddress() {
            // A university that emails four hundred graduates a broken link produces four
            // hundred unknown-token checks from four hundred different addresses. In total
            // that is a wall; per address it is one mistake each, which is what it was.
            List<AnomalyRules.ClientActivity> mailshot = List.of(
                    new AnomalyRules.ClientActivity("grad-1", 1, 0, 1),
                    new AnomalyRules.ClientActivity("grad-2", 1, 0, 1),
                    new AnomalyRules.ClientActivity("grad-3", 1, 0, 1),
                    new AnomalyRules.ClientActivity("grad-4", 2, 0, 2));

            assertThat(examine(mailshot, List.of())).isEmpty();
        }
    }

    @Nested
    @DisplayName("credential harvesting")
    class Harvesting {

        @Test
        @DisplayName("is reported when one address checks many different credentials")
        void firesOnConcentration() {
            List<Anomaly> found = examine(
                    List.of(new AnomalyRules.ClientActivity("client-a", 0, 40, 40)), List.of());

            assertThat(found).singleElement()
                    .extracting(Anomaly::kind).isEqualTo(AnomalyKind.CREDENTIAL_HARVESTING);
            assertThat(found.getFirst().distinctCredentials()).isEqualTo(40);
        }

        @Test
        @DisplayName("says out loud that this may be an employer doing its job")
        void admitsTheInnocentExplanation() {
            // The wording matters. Every one of these checks needed a token a holder chose to
            // share, and a finding phrased as an accusation would get somebody's employer
            // blocked over a shortlist.
            List<Anomaly> found = examine(
                    List.of(new AnomalyRules.ClientActivity("client-a", 0, 30, 30)), List.of());

            assertThat(found.getFirst().detail()).contains("employer", "shortlist");
        }

        @Test
        @DisplayName("is not reported for one employer checking three candidates")
        void doesNotFireOnOrdinaryUse() {
            assertThat(examine(
                    List.of(new AnomalyRules.ClientActivity("client-a", 0, 3, 3)), List.of()))
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("the report as a whole")
    class Report {

        @Test
        @DisplayName("is empty when nothing unusual happened")
        void quietWhenQuiet() {
            assertThat(examine(
                    List.of(new AnomalyRules.ClientActivity("client-a", 0, 2, 5),
                            new AnomalyRules.ClientActivity("client-b", 1, 1, 4)),
                    List.of()))
                    .isEmpty();
        }

        @Test
        @DisplayName("never contains a raw address, only the hash it was given")
        void carriesNoIdentifiers() {
            // The subject is whatever the read model supplied, and the read model supplies the
            // salted hash the verification log stores. Pinned here so that a future change
            // which starts passing raw addresses fails a test rather than shipping.
            List<Anomaly> found = examine(
                    List.of(new AnomalyRules.ClientActivity("9f2c1a7d3b4e", 20, 0, 20)),
                    List.of());

            assertThat(found.getFirst().subject()).isEqualTo("9f2c1a7d3b4e");
            assertThat(found.getFirst().detail()).doesNotContain("9f2c1a7d3b4e");
        }

        @Test
        @DisplayName("puts one address's two problems on two separate findings")
        void separatesKinds() {
            List<Anomaly> found = examine(
                    List.of(new AnomalyRules.ClientActivity("client-a", 50, 60, 110)),
                    List.of());

            assertThat(found).hasSize(2)
                    .extracting(Anomaly::kind)
                    .containsExactlyInAnyOrder(
                            AnomalyKind.TOKEN_ENUMERATION, AnomalyKind.CREDENTIAL_HARVESTING);
        }
    }

    @Nested
    @DisplayName("thresholds")
    class Thresholds {

        @Test
        @DisplayName("of zero are refused rather than silently clamped")
        void refuseZero() {
            // A zero threshold fires on the first legitimate request and makes the whole
            // feature noise. It almost certainly means a misread configuration file, so it
            // fails loudly at startup rather than quietly at three in the morning.
            assertThatThrownBy(() -> new AnomalyRules.Thresholds(0, 25))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("at least 1");

            assertThatThrownBy(() -> new AnomalyRules.Thresholds(10, 0))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("an anomaly refuses to exist")
    class Invariants {

        @Test
        @DisplayName("without a window that makes sense")
        void withoutAWindow() {
            assertThatThrownBy(() -> new Anomaly(AnomalyKind.TAMPERING, "SIGNATURE", 1, 0,
                    TO, FROM, "backwards"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("without a subject")
        void withoutASubject() {
            assertThatThrownBy(() -> new Anomaly(AnomalyKind.TAMPERING, " ", 1, 0,
                    FROM, TO, "no subject"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
