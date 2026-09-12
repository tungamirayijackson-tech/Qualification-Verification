package zw.ac.qvs.credential.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import zw.ac.qvs.testsupport.Requirement;

/**
 * A national identity number, and the one form the register hashes it in.
 *
 * <p>The canonical form is the point of this type. The number is never stored — only a salted
 * hash of it — so a person is findable only if every path hashes them identically. People write
 * the same number as {@code 63-1234567 K 42}, {@code 631234567K42} and {@code 63 1234567 k 42},
 * and a register that treated those as three people would be quietly useless: a registrar would
 * search for a graduate who is plainly there and be told nothing was found.
 */
@Requirement({"FR-01", "FR-03"})
class NationalIdTest {

    @Nested
    @DisplayName("the same person, written differently")
    class SamePerson {

        @ParameterizedTest
        @ValueSource(strings = {
            "63-1234567K42",
            "63-1234567 K 42",
            "631234567K42",
            "63 1234567 K 42",
            "63 1234567k42",
            "  63-1234567 k 42  ",
            "63-1234567-K-42"
        })
        @DisplayName("hashes to one canonical form")
        void oneCanonicalForm(String written) {
            assertThat(NationalId.parse(written).canonical()).isEqualTo("631234567K42");
        }

        @Test
        @DisplayName("including when the check letter arrives in lower case")
        void foldsTheLetter() {
            assertThat(NationalId.parse("08-2345678m17").canonical())
                    .isEqualTo(NationalId.parse("08-2345678M17").canonical());
        }

        @Test
        @DisplayName("and a six-digit serial is as valid as a seven-digit one")
        void acceptsSixOrSevenDigits() {
            assertThat(NationalId.parse("25-345678P08").canonical()).isEqualTo("25345678P08");
            assertThat(NationalId.parse("25-3456789P08").canonical()).isEqualTo("253456789P08");
        }
    }

    @Nested
    @DisplayName("not a national ID at all")
    class Refusals {

        @ParameterizedTest
        @ValueSource(strings = {
            "8001015009087",      // the thirteen digits this system used to take
            "63-1234567",         // no check letter, no district
            "63-12345K42",        // serial too short
            "63-12345678K42",     // serial too long
            "6-1234567K42",       // registration office too short
            "63-1234567KK42",     // two letters
            "63-1234567K4",       // district too short
            "63-1234567K423",     // district too long
            "abc",
            ""
        })
        @DisplayName("is refused")
        void refused(String written) {
            assertThatThrownBy(() -> NationalId.parse(written))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("including null")
        void refusesNull() {
            assertThatThrownBy(() -> NationalId.parse(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("and the refusal never repeats the number back")
        void doesNotEchoTheNumber() {
            // This message reaches logs and 400 responses. An identity number is the one field
            // in this system that must not travel in either.
            // A number distinct from the example the message carries, or this would fail on
            // the helpful part of the sentence rather than on a leak.
            assertThatThrownBy(() -> NationalId.parse("63-9876543K4"))
                    .hasMessageNotContaining("9876543");
        }
    }

    @Test
    @DisplayName("shows the number back the way the card is punctuated")
    void formatsForReading() {
        assertThat(NationalId.parse("631234567K42").formatted()).isEqualTo("63-1234567 K 42");
        assertThat(NationalId.parse("25345678P08").formatted()).isEqualTo("25-345678 P 08");
    }

    @Test
    @DisplayName("does not put the number in its own toString, where nobody would expect it")
    void toStringHidesIt() {
        // Value objects end up inside exception messages and log lines by accident far more
        // often than by design, and that is exactly when this one must stay quiet.
        assertThat(NationalId.parse("63-9876543K42").toString()).doesNotContain("9876543");
    }

    @Test
    @DisplayName("answers the shape question without throwing, for callers that only want to ask")
    void wellFormedCheck() {
        assertThat(NationalId.isWellFormed("63-1234567K42")).isTrue();
        assertThat(NationalId.isWellFormed("8001015009087")).isFalse();
        assertThat(NationalId.isWellFormed(null)).isFalse();
    }
}
