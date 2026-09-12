package zw.ac.qvs.verification.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import zw.ac.qvs.testsupport.Requirement;

@Requirement("FR-05")
class CredentialClaimsTest {

    private static CredentialClaims example() {
        return new CredentialClaims(
                "ZW-UNI-2026-000481",
                "urn:qvs:inst:0142",
                "9f2c00000000000000000000000000000000000000000000000000000000a100",
                "Thandeka N. Mahlangu",
                "BSc Computer Science",
                7,
                360,
                LocalDate.of(2026, 4, 11),
                "inst-0142-2026-a");
    }

    @Test
    @DisplayName("emits keys in fixed alphabetical order with no whitespace")
    void canonicalShape() {
        String json = example().canonicalJson();

        assertThat(json).isEqualTo(
                "{\"awd\":\"2026-04-11\","
                        + "\"crd\":360,"
                        + "\"iss\":\"urn:qvs:inst:0142\","
                        + "\"kid\":\"inst-0142-2026-a\","
                        + "\"nam\":\"Thandeka N. Mahlangu\","
                        + "\"nqf\":7,"
                        + "\"qua\":\"BSc Computer Science\","
                        + "\"ser\":\"ZW-UNI-2026-000481\","
                        + "\"sub\":\"9f2c00000000000000000000000000000000000000000000000000000000a100\"}");
    }

    @Test
    @DisplayName("is byte-for-byte reproducible")
    void deterministic() {
        // The whole signature scheme rests on this. If two serialisations of identical claims
        // could differ, a signature made over one would fail against the other and the failure
        // would be indistinguishable from tampering.
        assertThat(example().canonicalBytes()).isEqualTo(example().canonicalBytes());
    }

    @Test
    @DisplayName("numbers are emitted as integers, not quoted and not decimal")
    void numbersAreBare() {
        assertThat(example().canonicalJson())
                .contains("\"nqf\":7")
                .contains("\"crd\":360")
                .doesNotContain("\"nqf\":\"7\"")
                .doesNotContain("7.0");
    }

    @Test
    @DisplayName("dates are ISO-8601, never locale-dependent")
    void datesAreIso() {
        assertThat(example().canonicalJson()).contains("\"awd\":\"2026-04-11\"");
    }

    @Test
    @DisplayName("bytes are UTF-8")
    void utf8() {
        CredentialClaims accented = new CredentialClaims(
                "ZW-UNI-2026-000482", "urn:qvs:inst:0142",
                "9f2c00000000000000000000000000000000000000000000000000000000a100",
                "Renée Nkosi", "BA Politiek", 7, 360,
                LocalDate.of(2026, 4, 11), "inst-0142-2026-a");

        assertThat(accented.canonicalBytes())
                .isEqualTo(accented.canonicalJson().getBytes(StandardCharsets.UTF_8));
        assertThat(accented.canonicalJson()).contains("Renée");
    }

    @Nested
    @DisplayName("every field participates in the signed bytes")
    class MutationChangesBytes {

        private final String baseline = example().canonicalJson();

        @Test
        void serial() {
            assertThat(with(c -> new CredentialClaims("ZW-UNI-2026-000999", c.issuerUrn(),
                    c.subjectHash(), c.holderName(), c.qualification(), c.nqfLevel(),
                    c.credits(), c.awardedOn(), c.keyId()))).isNotEqualTo(baseline);
        }

        @Test
        void holderName() {
            // The attack this blocks: change the name on a genuine award to your own.
            assertThat(with(c -> new CredentialClaims(c.serial(), c.issuerUrn(), c.subjectHash(),
                    "Someone Else", c.qualification(), c.nqfLevel(), c.credits(),
                    c.awardedOn(), c.keyId()))).isNotEqualTo(baseline);
        }

        @Test
        void nqfLevel() {
            // The attack this blocks: promote a level 7 degree to a level 9 one.
            assertThat(with(c -> new CredentialClaims(c.serial(), c.issuerUrn(), c.subjectHash(),
                    c.holderName(), c.qualification(), 9, c.credits(),
                    c.awardedOn(), c.keyId()))).isNotEqualTo(baseline);
        }

        @Test
        void qualification() {
            assertThat(with(c -> new CredentialClaims(c.serial(), c.issuerUrn(), c.subjectHash(),
                    c.holderName(), "MBBS Medicine", c.nqfLevel(), c.credits(),
                    c.awardedOn(), c.keyId()))).isNotEqualTo(baseline);
        }

        @Test
        void awardDate() {
            assertThat(with(c -> new CredentialClaims(c.serial(), c.issuerUrn(), c.subjectHash(),
                    c.holderName(), c.qualification(), c.nqfLevel(), c.credits(),
                    LocalDate.of(2025, 4, 11), c.keyId()))).isNotEqualTo(baseline);
        }

        @Test
        void issuer() {
            assertThat(with(c -> new CredentialClaims(c.serial(), "urn:qvs:inst:9999",
                    c.subjectHash(), c.holderName(), c.qualification(), c.nqfLevel(),
                    c.credits(), c.awardedOn(), c.keyId()))).isNotEqualTo(baseline);
        }

        @Test
        void keyId() {
            assertThat(with(c -> new CredentialClaims(c.serial(), c.issuerUrn(), c.subjectHash(),
                    c.holderName(), c.qualification(), c.nqfLevel(), c.credits(),
                    c.awardedOn(), "inst-0142-2029-b"))).isNotEqualTo(baseline);
        }

        private String with(java.util.function.UnaryOperator<CredentialClaims> mutation) {
            return mutation.apply(example()).canonicalJson();
        }
    }

    @Nested
    @DisplayName("JSON escaping")
    class Escaping {

        @Test
        @DisplayName("a quote in a name cannot break out of its string")
        void quotesEscaped() {
            String json = named("Ann \"Nan\" O'Brien").canonicalJson();

            assertThat(json).contains("\"nam\":\"Ann \\\"Nan\\\" O'Brien\"");
        }

        @Test
        @DisplayName("a backslash is escaped rather than swallowed")
        void backslashEscaped() {
            String json = named("A\\B").canonicalJson();

            assertThat(json).contains("\"nam\":\"A\\\\B\"");
        }

        @Test
        @DisplayName("control characters become u-escapes")
        void controlCharacters() {
            String json = named("Line\nBreak\tTab").canonicalJson();

            assertThat(json).contains("Line\\nBreak\\tTab");
        }

        @Test
        @DisplayName("an injected key cannot forge a second field")
        void injectionAttempt() {
            // A name crafted to close its string and open a new claim. Escaping means the
            // whole thing stays one string value, so the signature covers it as written.
            CredentialClaims hostile = named("X\",\"nqf\":10,\"x\":\"");

            String json = hostile.canonicalJson();

            assertThat(json).contains("\"nqf\":7");
            assertThat(json).doesNotContain("\"nqf\":10");
        }

        private CredentialClaims named(String name) {
            return new CredentialClaims("ZW-UNI-2026-000481", "urn:qvs:inst:0142",
                    "9f2c00000000000000000000000000000000000000000000000000000000a100",
                    name, "BSc Computer Science", 7, 360,
                    LocalDate.of(2026, 4, 11), "inst-0142-2026-a");
        }
    }

    @Nested
    @DisplayName("invariants")
    class Invariants {

        @Test
        void rejectsOutOfRangeNqf() {
            assertThatThrownBy(() -> new CredentialClaims("ZW-UNI-2026-000481",
                    "urn:qvs:inst:0142", "9f2c", "Name", "Qual", 11, 360,
                    LocalDate.of(2026, 4, 11), "kid"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("nqf");
        }

        @Test
        void rejectsNonPositiveCredits() {
            assertThatThrownBy(() -> new CredentialClaims("ZW-UNI-2026-000481",
                    "urn:qvs:inst:0142", "9f2c", "Name", "Qual", 7, 0,
                    LocalDate.of(2026, 4, 11), "kid"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("crd");
        }

        @Test
        void rejectsMissingFields() {
            assertThatThrownBy(() -> new CredentialClaims(" ", "urn:qvs:inst:0142", "9f2c",
                    "Name", "Qual", 7, 360, LocalDate.of(2026, 4, 11), "kid"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("ser");

            assertThatThrownBy(() -> new CredentialClaims("ZW-UNI-2026-000481",
                    "urn:qvs:inst:0142", "9f2c", "Name", "Qual", 7, 360, null, "kid"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("awd");
        }
    }
}
