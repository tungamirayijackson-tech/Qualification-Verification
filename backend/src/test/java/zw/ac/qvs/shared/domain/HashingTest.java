package zw.ac.qvs.shared.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class HashingTest {

    // Known-answer test against the published SHA-256 of the empty string. If someone
    // "optimises" the digest later, this fails before the ledger silently re-chains.
    private static final String EMPTY_SHA256 =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    @Test
    @DisplayName("matches the published digest for the empty string")
    void knownAnswer() {
        assertThat(Hashing.sha256Hex("")).isEqualTo(EMPTY_SHA256);
    }

    @Test
    @DisplayName("produces 64 lower-case hex characters")
    void shape() {
        String digest = Hashing.sha256Hex("CREDENTIAL_ISSUED|ZW-UNI-2026-000481");

        assertThat(digest).hasSize(Hashing.HEX_LENGTH).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("is stable across calls and identical for string and byte input")
    void deterministic() {
        String text = "seq=4181|actor=registrar:14";

        assertThat(Hashing.sha256Hex(text))
                .isEqualTo(Hashing.sha256Hex(text))
                .isEqualTo(Hashing.sha256Hex(text.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("a single changed character changes the digest")
    void avalanche() {
        assertThat(Hashing.sha256Hex("nqf:7")).isNotEqualTo(Hashing.sha256Hex("nqf:8"));
    }

    @Test
    @DisplayName("rejects null rather than hashing a placeholder")
    void rejectsNull() {
        assertThatThrownBy(() -> Hashing.sha256Hex((String) null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Hashing.sha256Hex((byte[]) null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("constant-time compare agrees with equality and is null-safe")
    void constantTimeCompare() {
        String digest = Hashing.sha256Hex("token");

        assertThat(Hashing.constantTimeEquals(digest, digest)).isTrue();
        assertThat(Hashing.constantTimeEquals(digest, Hashing.sha256Hex("other"))).isFalse();
        assertThat(Hashing.constantTimeEquals(null, digest)).isFalse();
        assertThat(Hashing.constantTimeEquals(digest, null)).isFalse();
        assertThat(Hashing.constantTimeEquals(null, null)).isFalse();
    }
}
