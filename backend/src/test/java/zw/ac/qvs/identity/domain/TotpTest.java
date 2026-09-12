package zw.ac.qvs.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import zw.ac.qvs.testsupport.Requirement;

/**
 * TOTP checked against RFC 6238's own published test vectors.
 *
 * <p>This is why implementing the algorithm rather than importing it is defensible: the RFC
 * publishes the expected output for a known secret at known instants, so the implementation is
 * proved correct rather than assumed correct.
 */
@Requirement("FR-10")
class TotpTest {

    /**
     * The RFC 6238 appendix B secret: the ASCII string "12345678901234567890", base32-encoded.
     */
    private static final String RFC_SECRET =
            Totp.base32Encode("12345678901234567890".getBytes(StandardCharsets.US_ASCII));

    @ParameterizedTest(name = "at epoch second {0} the code is {1}")
    @CsvSource({
        "59,          287082",
        "1111111109,  081804",
        "1111111111,  050471",
        "1234567890,  005924",
        "2000000000,  279037",
        "20000000000, 353130"
    })
    @DisplayName("matches the RFC 6238 test vectors for HMAC-SHA1")
    void rfcVectors(long epochSecond, String expected) {
        assertThat(Totp.codeAt(RFC_SECRET, Instant.ofEpochSecond(epochSecond)))
                .isEqualTo(expected);
    }

    @Test
    @DisplayName("produces six digits, zero-padded when the value is short")
    void sixDigits() {
        assertThat(Totp.codeAt(RFC_SECRET, Instant.ofEpochSecond(1234567890)))
                .hasSize(6)
                .isEqualTo("005924");
    }

    @Test
    @DisplayName("the current code verifies")
    void currentCodeVerifies() {
        Instant now = Instant.ofEpochSecond(1_700_000_000L);

        assertThat(Totp.verify(RFC_SECRET, Totp.codeAt(RFC_SECRET, now), now)).isTrue();
    }

    @Test
    @DisplayName("tolerates one step of clock skew in either direction")
    void toleratesSmallSkew() {
        Instant now = Instant.ofEpochSecond(1_700_000_000L);
        Instant justBefore = now.minusSeconds(Totp.STEP_SECONDS);
        Instant justAfter = now.plusSeconds(Totp.STEP_SECONDS);

        assertThat(Totp.verify(RFC_SECRET, Totp.codeAt(RFC_SECRET, justBefore), now)).isTrue();
        assertThat(Totp.verify(RFC_SECRET, Totp.codeAt(RFC_SECRET, justAfter), now)).isTrue();
    }

    @Test
    @DisplayName("rejects a code from outside the window")
    void rejectsStaleCode() {
        // Two steps away. Widening the window past one step multiplies an attacker's odds,
        // so the boundary is asserted rather than left to whatever the constant happens to be.
        Instant now = Instant.ofEpochSecond(1_700_000_000L);
        Instant tooEarly = now.minusSeconds(Totp.STEP_SECONDS * 2L);

        assertThat(Totp.verify(RFC_SECRET, Totp.codeAt(RFC_SECRET, tooEarly), now)).isFalse();
    }

    @Test
    @DisplayName("rejects a code generated from a different secret")
    void rejectsWrongSecret() {
        Instant now = Instant.ofEpochSecond(1_700_000_000L);
        String otherSecret = Totp.generateSecret();

        assertThat(Totp.verify(RFC_SECRET, Totp.codeAt(otherSecret, now), now)).isFalse();
    }

    @Test
    @DisplayName("rejects malformed input rather than throwing")
    void rejectsMalformed() {
        Instant now = Instant.ofEpochSecond(1_700_000_000L);

        assertThat(Totp.verify(RFC_SECRET, null, now)).isFalse();
        assertThat(Totp.verify(RFC_SECRET, "12345", now)).isFalse();
        assertThat(Totp.verify(RFC_SECRET, "1234567", now)).isFalse();
        assertThat(Totp.verify(null, "123456", now)).isFalse();
    }

    @Test
    @DisplayName("generated secrets are base32 and round-trip through decoding")
    void generatedSecrets() {
        String secret = Totp.generateSecret();

        assertThat(secret).matches("[A-Z2-7]+");
        assertThat(Totp.base32Decode(secret)).hasSize(20);
    }

    @Test
    @DisplayName("base32 encoding round-trips arbitrary bytes")
    void base32RoundTrip() {
        byte[] original = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

        assertThat(Totp.base32Decode(Totp.base32Encode(original))).isEqualTo(original);
    }
}
