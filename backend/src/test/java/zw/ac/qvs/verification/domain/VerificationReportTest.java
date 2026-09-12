package zw.ac.qvs.verification.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import zw.ac.qvs.testsupport.Requirement;

/**
 * The canonical form of a report, which is the part that has to be exactly right.
 *
 * <p>A canonical form is only worth having if it is pinned by tests that fail when it changes,
 * because the cost of an accidental change is not a broken build — it is every report already
 * issued becoming unverifiable, silently, with no error anywhere until somebody tries to check
 * one and is told it was forged.
 */
@Requirement("FR-12")
class VerificationReportTest {

    private static final Instant VERIFIED_AT = Instant.parse("2026-09-07T10:15:30Z");
    private static final String ENTRY_HASH =
            "9f2c1a7d3b4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8";

    private static VerificationReport valid() {
        return new VerificationReport(
                "ZW-PR0142-2026-000001",
                "VALID",
                VERIFIED_AT,
                4291L,
                ENTRY_HASH,
                "inst-0142-2026-01",
                "Example University",
                "BSc Computer Science",
                7,
                LocalDate.of(2026, 4, 11),
                "T.N.M.",
                null,
                null);
    }

    private static VerificationReport notFound() {
        return new VerificationReport(null, "NOT_FOUND", VERIFIED_AT, 4292L, ENTRY_HASH,
                null, null, null, null, null, null, null, null);
    }

    @Nested
    @DisplayName("the canonical form")
    class CanonicalForm {

        @Test
        @DisplayName("is exactly this, byte for byte")
        void isExact() {
            // Written out in full rather than assembled from the record's own fields. A test
            // that builds the expected string the same way the production code does would pass
            // no matter what either of them did.
            assertThat(valid().canonicalJson()).isEqualTo(
                    "{\"awd\":\"2026-04-11\","
                            + "\"ent\":\"" + ENTRY_HASH + "\","
                            + "\"hld\":\"T.N.M.\","
                            + "\"ins\":\"Example University\","
                            + "\"kid\":\"inst-0142-2026-01\","
                            + "\"led\":4291,"
                            + "\"nqf\":7,"
                            + "\"qua\":\"BSc Computer Science\","
                            + "\"ser\":\"ZW-PR0142-2026-000001\","
                            + "\"vat\":\"2026-09-07T10:15:30Z\","
                            + "\"vdt\":\"VALID\"}");
        }

        @Test
        @DisplayName("orders keys alphabetically, whatever order the record declares them in")
        void isAlphabetical() {
            String json = valid().canonicalJson();
            String[] keys = {"awd", "ent", "hld", "ins", "kid", "led", "nqf", "qua", "ser",
                    "vat", "vdt"};

            int previous = -1;
            for (String key : keys) {
                int at = json.indexOf("\"" + key + "\":");
                assertThat(at).as("%s is present", key).isGreaterThan(-1);
                assertThat(at).as("%s comes after the key before it", key).isGreaterThan(previous);
                previous = at;
            }
        }

        @Test
        @DisplayName("is stable across repeated calls")
        void isStable() {
            VerificationReport report = valid();
            assertThat(report.canonicalJson()).isEqualTo(report.canonicalJson());
            assertThat(report.canonicalBytes()).isEqualTo(report.canonicalBytes());
        }

        @Test
        @DisplayName("writes numbers without quotes or decimal points")
        void numbersAreNumbers() {
            // A ledger sequence rendered as 4291.0 would still parse, and would still break
            // every signature made before somebody changed how it was written.
            assertThat(valid().canonicalJson()).contains("\"led\":4291", "\"nqf\":7");
            assertThat(valid().canonicalJson()).doesNotContain("4291.0", "\"4291\"");
        }

        @Test
        @DisplayName("escapes only what RFC 8259 requires")
        void escapesMinimally() {
            VerificationReport awkward = new VerificationReport(
                    "ZW-PR0142-2026-000002", "VALID", VERIFIED_AT, 1L, ENTRY_HASH, "k",
                    "The \"Open\" University", "Law\\Ethics", 8, LocalDate.of(2026, 1, 1),
                    "A.B.", null, null);

            String json = awkward.canonicalJson();
            assertThat(json).contains("\"ins\":\"The \\\"Open\\\" University\"");
            assertThat(json).contains("\"qua\":\"Law\\\\Ethics\"");
            // A forward slash and a non-ASCII letter are legal unescaped, and escaping them
            // optionally is exactly how two implementations drift apart.
            assertThat(new VerificationReport("s", "VALID", VERIFIED_AT, 1L, ENTRY_HASH, "k",
                    "Université de Paris", "Droit/Éthique", 8, LocalDate.of(2026, 1, 1), "A.B.",
                    null, null).canonicalJson())
                    .contains("Université de Paris")
                    .contains("Droit/Éthique");
        }
    }

    @Nested
    @DisplayName("the not-found report")
    class NotFoundReport {

        @Test
        @DisplayName("carries the four facts that always exist, and nothing else")
        void carriesOnlyWhatAlwaysExists() {
            assertThat(notFound().canonicalJson()).isEqualTo(
                    "{\"ent\":\"" + ENTRY_HASH + "\","
                            + "\"led\":4292,"
                            + "\"vat\":\"2026-09-07T10:15:30Z\","
                            + "\"vdt\":\"NOT_FOUND\"}");
        }

        @Test
        @DisplayName("omits absent fields rather than writing nulls")
        void omitsRatherThanNulls() {
            // The distinction matters. A null would be a place for a value to appear later by
            // accident; an omission is nothing at all.
            assertThat(notFound().canonicalJson()).doesNotContain("null", "\"ser\"", "\"hld\"",
                    "\"ins\"", "\"qua\"", "\"kid\"", "\"nqf\"", "\"awd\"");
        }

        @Test
        @DisplayName("knows it describes no credential")
        void knowsItDescribesNoCredential() {
            assertThat(notFound().describesCredential()).isFalse();
            assertThat(valid().describesCredential()).isTrue();
        }
    }

    @Nested
    @DisplayName("a revoked report")
    class RevokedReport {

        @Test
        @DisplayName("carries the reason and the date, in the signed statement")
        void carriesRevocation() {
            VerificationReport revoked = new VerificationReport(
                    "ZW-PR0142-2026-000003", "REVOKED", VERIFIED_AT, 77L, ENTRY_HASH,
                    "inst-0142-2026-01", "Example University", "BCom", 7,
                    LocalDate.of(2025, 12, 5), "S.N.", "ISSUED_IN_ERROR",
                    Instant.parse("2026-08-01T09:00:00Z"));

            // Inside the signature, so a report cannot have its revocation quietly removed.
            assertThat(revoked.canonicalJson())
                    .contains("\"rea\":\"ISSUED_IN_ERROR\"")
                    .contains("\"rat\":\"2026-08-01T09:00:00Z\"");
        }
    }

    @Nested
    @DisplayName("a report refuses to exist without")
    class Invariants {

        @Test
        @DisplayName("a verdict")
        void withoutVerdict() {
            assertThatThrownBy(() -> new VerificationReport(null, " ", VERIFIED_AT, 1L,
                    ENTRY_HASH, null, null, null, null, null, null, null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("verdict");
        }

        @Test
        @DisplayName("a time")
        void withoutTime() {
            assertThatThrownBy(() -> new VerificationReport(null, "VALID", null, 1L, ENTRY_HASH,
                    null, null, null, null, null, null, null, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("a ledger entry to point at")
        void withoutLedgerEntry() {
            // Without this the report would be a claim with nothing behind it: the whole reason
            // a verifier can act on one is that the check it describes is in the chain.
            assertThatThrownBy(() -> new VerificationReport(null, "VALID", VERIFIED_AT, 1L, null,
                    null, null, null, null, null, null, null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("ledger");
        }
    }

    @Nested
    @DisplayName("the signed wrapper")
    class Signed {

        @Test
        @DisplayName("refuses a signature that does not say which key made it")
        void requiresKeyId() {
            assertThatThrownBy(() ->
                    new SignedVerificationReport(valid(), " ", "eyJhbGciOiJFZERTQSJ9..sig"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("key");
        }

        @Test
        @DisplayName("refuses a statement with no signature")
        void requiresSignature() {
            assertThatThrownBy(() -> new SignedVerificationReport(valid(), "qvs-report-1", null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
