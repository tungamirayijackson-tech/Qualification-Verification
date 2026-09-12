package zw.ac.qvs.verification.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zw.ac.qvs.shared.domain.Hashing;
import zw.ac.qvs.testsupport.Requirement;

/**
 * The mechanism behind Decision 09-A: consent, expiring and withdrawable.
 */
@Requirement("FR-06")
class ShareTokenTest {

    private static final UUID CREDENTIAL = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final Instant ISSUED = Instant.parse("2026-09-06T09:00:00Z");
    private static final Instant EXPIRES = Instant.parse("2026-10-06T09:00:00Z");

    @Test
    @DisplayName("mints a secret and stores only its hash")
    void mintStoresOnlyAHash() {
        // The property that makes a database disclosure useless to whoever obtains it: the
        // stored record cannot be turned back into a working link.
        var minted = ShareToken.mint(CREDENTIAL, ISSUED, EXPIRES, "for Acme Ltd");

        assertThat(minted.secret()).isNotBlank();
        assertThat(minted.record().tokenHash())
                .isEqualTo(Hashing.sha256Hex(minted.secret()))
                .matches("[0-9a-f]{64}")
                .isNotEqualTo(minted.secret());
    }

    @Test
    @DisplayName("every minted secret is different")
    void secretsAreUnpredictable() {
        var first = ShareToken.mint(CREDENTIAL, ISSUED, EXPIRES, null);
        var second = ShareToken.mint(CREDENTIAL, ISSUED, EXPIRES, null);

        assertThat(first.secret()).isNotEqualTo(second.secret());
        assertThat(first.record().id()).isNotEqualTo(second.record().id());
    }

    @Test
    @DisplayName("the secret carries 128 bits of entropy")
    void entropy() {
        var minted = ShareToken.mint(CREDENTIAL, ISSUED, EXPIRES, null);

        // base64url of 16 bytes, unpadded.
        assertThat(minted.secret()).hasSize(22).matches("[A-Za-z0-9_-]{22}");
        assertThat(ShareToken.TOKEN_BYTES).isEqualTo(16);
    }

    @Test
    @DisplayName("is usable up to the moment it expires, and not after")
    void expiry() {
        ShareToken token = ShareToken.mint(CREDENTIAL, ISSUED, EXPIRES, null).record();

        assertThat(token.isUsableAt(ISSUED)).isTrue();
        assertThat(token.isUsableAt(EXPIRES.minusSeconds(1))).isTrue();
        assertThat(token.isUsableAt(EXPIRES)).isFalse();
        assertThat(token.isUsableAt(EXPIRES.plusSeconds(1))).isFalse();
    }

    @Test
    @DisplayName("a withdrawn token stops being usable even before it expires")
    void withdrawal() {
        ShareToken token = ShareToken.mint(CREDENTIAL, ISSUED, EXPIRES, null).record();

        ShareToken withdrawn = token.revoked(ISSUED.plusSeconds(60));

        assertThat(withdrawn.isUsableAt(ISSUED.plusSeconds(120))).isFalse();
        assertThat(withdrawn.revokedAt()).isEqualTo(ISSUED.plusSeconds(60));
        // Withdrawal changes nothing else: the same token, still pointing at the same record.
        assertThat(withdrawn.id()).isEqualTo(token.id());
        assertThat(withdrawn.tokenHash()).isEqualTo(token.tokenHash());
        assertThat(withdrawn.credentialId()).isEqualTo(token.credentialId());
    }

    @Test
    @DisplayName("refuses to exist without a credential, a hash or a lifetime")
    void invariants() {
        assertThatThrownBy(() -> new ShareToken(
                UUID.randomUUID(), null, "a".repeat(64), ISSUED, EXPIRES, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("credential");

        assertThatThrownBy(() -> new ShareToken(
                UUID.randomUUID(), CREDENTIAL, "not-a-hash", ISSUED, EXPIRES, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SHA-256");

        assertThatThrownBy(() -> new ShareToken(
                UUID.randomUUID(), CREDENTIAL, "a".repeat(64), ISSUED, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lifetime");
    }

    @Test
    @DisplayName("refuses a token that expires before it was issued")
    void expiryMustFollowIssue() {
        // A token with an impossible lifetime is either a bug or an attempt to mint something
        // that behaves oddly at the boundary. Neither should be storable.
        assertThatThrownBy(() -> new ShareToken(
                UUID.randomUUID(), CREDENTIAL, "a".repeat(64), EXPIRES, ISSUED, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expire after");
    }
}
