package zw.ac.qvs.credential.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import zw.ac.qvs.credential.domain.Credential;
import zw.ac.qvs.credential.domain.CredentialStatus;
import zw.ac.qvs.credential.domain.NqfLevel;
import zw.ac.qvs.credential.domain.Qualification;
import zw.ac.qvs.credential.domain.RevocationReason;
import zw.ac.qvs.credential.domain.Serial;
import zw.ac.qvs.ledger.application.AppendEntry;
import zw.ac.qvs.ledger.domain.LedgerAction;
import zw.ac.qvs.testsupport.InMemoryLedger;
import zw.ac.qvs.testsupport.Requirement;

/**
 * Withdrawing an award, and whose awards a registrar may withdraw.
 *
 * <p>The second half is the one worth having. Until it existed, the authorisation rule on this
 * path said REGISTRAR and stopped there — every registrar passed it, and "which registrar" was
 * never asked. Serials are structured and count from one, so a registrar at one university could
 * withdraw another university's award simply by naming it, and the ledger would record the
 * wronged institution as having done it.
 */
@Requirement({"FR-07", "FR-10"})
class RevokeCredentialTest {

    private static final UUID OURS = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID THEIRS = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID REGISTRAR = UUID.fromString("33333333-3333-4333-8333-333333333333");

    private Credentials credentials;
    private Qualifications qualifications;
    private InMemoryLedger ledger;
    private RevokeCredential revokeCredential;

    @BeforeEach
    void setUp() {
        credentials = new Credentials();
        qualifications = new Qualifications();
        ledger = new InMemoryLedger();
        revokeCredential = new RevokeCredential(credentials, qualifications,
                new AppendEntry(ledger, Clock.fixed(Instant.parse("2026-09-12T09:00:00Z"),
                        ZoneOffset.UTC)),
                Clock.fixed(Instant.parse("2026-09-12T09:00:00Z"), ZoneOffset.UTC));
    }

    /** A credential conferred by one institution, through a qualification it offers. */
    private Credential credentialOf(UUID institutionId, String serial) {
        UUID qualificationId = UUID.randomUUID();
        qualifications.save(new Qualification(qualificationId, institutionId,
                "BSc Computer Science", new NqfLevel(7), 360, null, false));

        Credential credential = new Credential(UUID.randomUUID(), new Serial(serial),
                qualificationId, UUID.randomUUID(), LocalDate.of(2026, 4, 11), "key-1",
                "detached-jws", "{}", CredentialStatus.ISSUED, null, null, null, null,
                Instant.parse("2026-04-11T00:00:00Z"));
        return credentials.save(credential);
    }

    private RevokeCredential.Command asRegistrarOf(UUID institution, String serial) {
        return new RevokeCredential.Command(new Serial(serial), RevocationReason.ISSUED_IN_ERROR,
                null, REGISTRAR, institution);
    }

    @Nested
    @DisplayName("a registrar's own institution")
    class OwnInstitution {

        @Test
        @DisplayName("can be withdrawn")
        void revokesOwn() {
            credentialOf(OURS, "ZW-PR0142-2026-000001");

            Credential revoked = revokeCredential.revoke(
                    asRegistrarOf(OURS, "ZW-PR0142-2026-000001"));

            assertThat(revoked.isRevoked()).isTrue();
            assertThat(ledger.withAction(LedgerAction.CREDENTIAL_REVOKED)).hasSize(1);
        }

        @Test
        @DisplayName("is refused a second time, so the trail shows one withdrawal")
        void refusesTwice() {
            credentialOf(OURS, "ZW-PR0142-2026-000001");
            revokeCredential.revoke(asRegistrarOf(OURS, "ZW-PR0142-2026-000001"));

            assertThatThrownBy(() -> revokeCredential.revoke(
                    asRegistrarOf(OURS, "ZW-PR0142-2026-000001")))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("another institution's credential")
    class SomebodyElses {

        @Test
        @DisplayName("cannot be withdrawn, however well-formed the serial")
        void refusesAnotherInstitution() {
            credentialOf(THEIRS, "ZW-PR0999-2026-000001");

            assertThatThrownBy(() -> revokeCredential.revoke(
                    asRegistrarOf(OURS, "ZW-PR0999-2026-000001")))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("is left untouched, and nothing is written to the ledger")
        void changesNothing() {
            Credential theirs = credentialOf(THEIRS, "ZW-PR0999-2026-000001");

            assertThatThrownBy(() -> revokeCredential.revoke(
                    asRegistrarOf(OURS, "ZW-PR0999-2026-000001")))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThat(credentials.findBySerial(theirs.serial()).orElseThrow().isRevoked())
                    .as("their award still stands")
                    .isFalse();
            assertThat(ledger.entries())
                    .as("and their institution is not recorded as having withdrawn anything")
                    .isEmpty();
        }

        @Test
        @DisplayName("is refused in the same words as a serial that does not exist")
        void indistinguishableFromUnknown() {
            // Serials are guessable by construction: ZW-PR0142-2026-000001 names the
            // institution and counts from one. If "not yours" read differently from "no such
            // thing", anybody with an account could enumerate every institution's output.
            credentialOf(THEIRS, "ZW-PR0999-2026-000001");

            String refusedTheirs = catchMessage(
                    () -> revokeCredential.revoke(asRegistrarOf(OURS, "ZW-PR0999-2026-000001")));
            String refusedUnknown = catchMessage(
                    () -> revokeCredential.revoke(asRegistrarOf(OURS, "ZW-PR0999-2026-000002")));

            assertThat(refusedTheirs).isEqualTo("no credential ZW-PR0999-2026-000001");
            assertThat(refusedUnknown).isEqualTo("no credential ZW-PR0999-2026-000002");
        }

        private String catchMessage(Runnable call) {
            try {
                call.run();
                throw new AssertionError("expected a refusal");
            } catch (IllegalArgumentException expected) {
                return expected.getMessage();
            }
        }
    }

    @Test
    @DisplayName("a caller bound to no institution is not narrowed, which is how imports work")
    void unscopedCallerIsUnrestricted() {
        // Null means "not bound to one institution" rather than "bound to nothing". The check
        // narrows a registrar; it must not quietly block a role that was never scoped.
        credentialOf(THEIRS, "ZW-PR0999-2026-000001");

        Credential revoked = revokeCredential.revoke(new RevokeCredential.Command(
                new Serial("ZW-PR0999-2026-000001"), RevocationReason.ISSUED_IN_ERROR, null,
                REGISTRAR, null));

        assertThat(revoked.isRevoked()).isTrue();
    }

    // ------------------------------------------------------------------ stubs

    private static final class Credentials implements CredentialRepository {
        private final Map<String, Credential> bySerial = new HashMap<>();

        @Override
        public Optional<Credential> findBySerial(Serial serial) {
            return Optional.ofNullable(bySerial.get(serial.value()));
        }

        @Override
        public Optional<Credential> findById(UUID id) {
            return bySerial.values().stream().filter(c -> c.id().equals(id)).findFirst();
        }

        @Override
        public List<Credential> findByHolder(UUID holderId) {
            return bySerial.values().stream().filter(c -> c.holderId().equals(holderId)).toList();
        }

        @Override
        public Credential save(Credential credential) {
            bySerial.put(credential.serial().value(), credential);
            return credential;
        }

        @Override
        public Credential insert(Credential credential) {
            return save(credential);
        }

        @Override
        public int reserveSerialSequence(String institutionCode, int year) {
            throw new UnsupportedOperationException("revocation mints no serials");
        }
    }

    private static final class Qualifications implements QualificationRepository {
        private final Map<UUID, Qualification> rows = new HashMap<>();

        @Override
        public Optional<Qualification> findById(UUID id) {
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public List<Qualification> findByInstitution(UUID institutionId) {
            throw new UnsupportedOperationException("revocation lists nothing");
        }

        @Override
        public Qualification save(Qualification qualification) {
            rows.put(qualification.id(), qualification);
            return qualification;
        }
    }
}
