package zw.ac.qvs.credential.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zw.ac.qvs.testsupport.Requirement;

/**
 * The credential record, and the one state change it permits.
 */
@Requirement("FR-07")
class CredentialTest {

    private static final UUID ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID QUALIFICATION = UUID.randomUUID();
    private static final UUID HOLDER = UUID.randomUUID();
    private static final UUID REGISTRAR = UUID.randomUUID();
    private static final Instant ISSUED_AT = Instant.parse("2026-04-12T08:00:00Z");
    private static final Instant REVOKED_AT = Instant.parse("2026-08-01T10:00:00Z");

    private static Credential issued() {
        return new Credential(ID, new Serial("ZW-PR0142-2026-000001"), QUALIFICATION, HOLDER,
                LocalDate.of(2026, 4, 11), "inst-0142-2026-a", "eyJhbGciOiJFZERTQSJ9..sig",
                "{\"ser\":\"ZW-PR0142-2026-000001\"}", CredentialStatus.ISSUED,
                null, null, null, null, ISSUED_AT);
    }

    @Test
    @DisplayName("revoking preserves the signature and the bytes it covers")
    void revocationPreservesTheSignature() {
        // FR-07's substance. Destroying the signature on revocation would erase the evidence
        // that the award was ever genuinely made, which is the opposite of what an audit
        // trail is for.
        Credential original = issued();

        Credential revoked = original.revoked(
                RevocationReason.ACADEMIC_MISCONDUCT, REVOKED_AT, REGISTRAR);

        assertThat(revoked.detachedJws()).isEqualTo(original.detachedJws());
        assertThat(revoked.payloadCanonical()).isEqualTo(original.payloadCanonical());
        assertThat(revoked.keyId()).isEqualTo(original.keyId());
        assertThat(revoked.awardedOn()).isEqualTo(original.awardedOn());
        assertThat(revoked.issuedAt()).isEqualTo(original.issuedAt());
    }

    @Test
    @DisplayName("revoking records when, why and by whom")
    void revocationIsAttributed() {
        Credential revoked = issued().revoked(
                RevocationReason.ISSUED_IN_ERROR, REVOKED_AT, REGISTRAR);

        assertThat(revoked.isRevoked()).isTrue();
        assertThat(revoked.status()).isEqualTo(CredentialStatus.REVOKED);
        assertThat(revoked.revokedAt()).isEqualTo(REVOKED_AT);
        assertThat(revoked.revokedBy()).isEqualTo(REGISTRAR);
        assertThat(revoked.revocationReason()).contains(RevocationReason.ISSUED_IN_ERROR);
    }

    @Test
    @DisplayName("an issued credential reports no revocation reason")
    void issuedHasNoReason() {
        assertThat(issued().isRevoked()).isFalse();
        assertThat(issued().revocationReason()).isEmpty();
    }

    @Test
    @DisplayName("a credential cannot be revoked twice")
    void doubleRevocationRefused() {
        // Accepting a second revocation would let the audit trail show two withdrawals of one
        // award, with two actors and two reasons, and no way to tell which one counted.
        Credential revoked = issued().revoked(RevocationReason.HOLDER_REQUEST, REVOKED_AT, null);

        assertThatThrownBy(() ->
                revoked.revoked(RevocationReason.OTHER, REVOKED_AT.plusSeconds(60), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already revoked");
    }

    @Test
    @DisplayName("a revocation must carry a reason and a timestamp")
    void revocationNeedsBoth() {
        assertThatThrownBy(() -> issued().revoked(null, REVOKED_AT, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reason");
        assertThatThrownBy(() ->
                issued().revoked(RevocationReason.ADMINISTRATIVE_ERROR, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timestamp");
    }

    @Test
    @DisplayName("a half-revoked record cannot be constructed at all")
    void noHalfRevokedStates() {
        // A record marked REVOKED with no reason, or ISSUED with a revocation date, is how a
        // register and its audit trail start disagreeing. The type refuses to hold one.
        assertThatThrownBy(() -> new Credential(ID, new Serial("ZW-PR0142-2026-000001"),
                QUALIFICATION, HOLDER, LocalDate.of(2026, 4, 11), "kid", "jws", "{}",
                CredentialStatus.REVOKED, null, null, null, null, ISSUED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("when and why");

        assertThatThrownBy(() -> new Credential(ID, new Serial("ZW-PR0142-2026-000001"),
                QUALIFICATION, HOLDER, LocalDate.of(2026, 4, 11), "kid", "jws", "{}",
                CredentialStatus.ISSUED, REVOKED_AT, RevocationReason.OTHER, null, null,
                ISSUED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot carry revocation detail");
    }

    @Test
    @DisplayName("refuses to exist without the things that make it verifiable")
    void invariants() {
        assertThatThrownBy(() -> new Credential(ID, null, QUALIFICATION, HOLDER,
                LocalDate.of(2026, 4, 11), "kid", "jws", "{}", CredentialStatus.ISSUED,
                null, null, null, null, ISSUED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("serial");

        assertThatThrownBy(() -> new Credential(ID, new Serial("ZW-PR0142-2026-000001"),
                QUALIFICATION, HOLDER, LocalDate.of(2026, 4, 11), " ", "jws", "{}",
                CredentialStatus.ISSUED, null, null, null, null, ISSUED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("which key signed it");

        assertThatThrownBy(() -> new Credential(ID, new Serial("ZW-PR0142-2026-000001"),
                QUALIFICATION, HOLDER, LocalDate.of(2026, 4, 11), "kid", " ", "{}",
                CredentialStatus.ISSUED, null, null, null, null, ISSUED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("signature");

        assertThatThrownBy(() -> new Credential(ID, new Serial("ZW-PR0142-2026-000001"),
                QUALIFICATION, HOLDER, LocalDate.of(2026, 4, 11), "kid", "jws", " ",
                CredentialStatus.ISSUED, null, null, null, null, ISSUED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bytes it signed");

        assertThatThrownBy(() -> new Credential(ID, new Serial("ZW-PR0142-2026-000001"),
                null, HOLDER, LocalDate.of(2026, 4, 11), "kid", "jws", "{}",
                CredentialStatus.ISSUED, null, null, null, null, ISSUED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("qualification and a holder");

        assertThatThrownBy(() -> new Credential(ID, new Serial("ZW-PR0142-2026-000001"),
                QUALIFICATION, HOLDER, null, "kid", "jws", "{}",
                CredentialStatus.ISSUED, null, null, null, null, ISSUED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("awardedOn");

        assertThatThrownBy(() -> new Credential(ID, new Serial("ZW-PR0142-2026-000001"),
                QUALIFICATION, HOLDER, LocalDate.of(2026, 4, 11), "kid", "jws", "{}",
                null, null, null, null, null, ISSUED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("status");
    }
}
