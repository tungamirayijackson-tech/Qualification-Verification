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
import zw.ac.qvs.credential.domain.Institution;
import zw.ac.qvs.ledger.application.AppendEntry;
import zw.ac.qvs.ledger.domain.LedgerAction;
import zw.ac.qvs.testsupport.InMemoryLedger;
import zw.ac.qvs.testsupport.Requirement;

/**
 * Admitting an awarding body.
 *
 * <p>The rules worth pinning down are the refusals. Letting an institution in is the act that
 * decides whose signature this system will vouch for, so the interesting question is not
 * whether a good one is accepted but whether a duplicate, a typo or a dead accreditation is
 * turned away — and whether the act is recorded either way.
 */
@Requirement("FR-01")
class OnboardInstitutionTest {

    private static final UUID ADMIN = UUID.fromString("44444444-4444-4444-8444-444444444444");

    private Institutions institutions;
    private InMemoryLedger ledger;
    private OnboardInstitution onboard;

    @BeforeEach
    void setUp() {
        institutions = new Institutions();
        ledger = new InMemoryLedger();
        onboard = new OnboardInstitution(institutions,
                new AppendEntry(ledger, Clock.fixed(Instant.parse("2026-09-07T10:00:00Z"),
                        ZoneOffset.UTC)));
    }

    private static OnboardInstitution.Command command(String name, String providerNumber) {
        return new OnboardInstitution.Command(
                name, "zw", providerNumber, LocalDate.now().plusYears(3), ADMIN);
    }

    @Nested
    @DisplayName("admitting an institution")
    class Admitting {

        @Test
        @DisplayName("stores it and gives it an identity")
        void stores() {
            Institution stored = onboard.onboard(command("Example University", "pr-0142"));

            assertThat(stored.id()).isNotNull();
            assertThat(institutions.rows).containsKey(stored.id());
            assertThat(stored.name()).isEqualTo("Example University");
        }

        @Test
        @DisplayName("gives it no signing key, so it cannot issue anything yet")
        void hasNoKey() {
            // The half-finished state is the correct one: admitted, visible, unable to sign.
            // Issuing a key is a separate act with its own audit entry.
            Institution stored = onboard.onboard(command("Example University", "pr-0142"));

            assertThat(stored.activeKeyId()).isNull();
            assertThat(stored.canIssueOn(LocalDate.now())).isFalse();
        }

        @Test
        @DisplayName("normalises the country code and the provider number")
        void normalises() {
            Institution stored = onboard.onboard(command("  Example University  ", " pr-0142 "));

            assertThat(stored.country()).isEqualTo("ZW");
            assertThat(stored.providerNumber()).isEqualTo("PR-0142");
            assertThat(stored.name()).isEqualTo("Example University");
        }

        @Test
        @DisplayName("is recorded in the ledger, against the administrator who did it")
        void isAudited() {
            Institution stored = onboard.onboard(command("Example University", "pr-0142"));

            assertThat(ledger.entries()).hasSize(1);
            assertThat(ledger.entries().getFirst().action())
                    .isEqualTo(LedgerAction.INSTITUTION_ONBOARDED);
            assertThat(ledger.entries().getFirst().subjectRef()).isEqualTo(stored.id().toString());
            assertThat(ledger.entries().getFirst().actorId()).isEqualTo(ADMIN);
        }
    }

    @Nested
    @DisplayName("refusals")
    class Refusals {

        @Test
        @DisplayName("a provider number that already belongs to somebody, naming who")
        void duplicateProviderNumber() {
            onboard.onboard(command("Example University", "PR-0142"));

            assertThatThrownBy(() -> onboard.onboard(command("Different Name", "pr-0142")))
                    .isInstanceOf(RegistrationRejected.class)
                    .hasMessageContaining("Example University")
                    .extracting(e -> ((RegistrationRejected) e).reason())
                    .isEqualTo(RegistrationRejected.Reason.DUPLICATE_PROVIDER_NUMBER);
        }

        @Test
        @DisplayName("an accreditation that has already lapsed")
        void alreadyLapsed() {
            // Storing one would record a mistake rather than a state anybody wanted: the
            // institution could never issue anything.
            var lapsed = new OnboardInstitution.Command(
                    "Lapsed College", "ZW", "PR-0999", LocalDate.now().minusDays(1), ADMIN);

            assertThatThrownBy(() -> onboard.onboard(lapsed))
                    .isInstanceOf(RegistrationRejected.class)
                    .extracting(e -> ((RegistrationRejected) e).reason())
                    .isEqualTo(RegistrationRejected.Reason.ACCREDITATION_ALREADY_LAPSED);
        }

        @Test
        @DisplayName("a missing provider number")
        void missingProviderNumber() {
            assertThatThrownBy(() -> onboard.onboard(command("Example University", "   ")))
                    .isInstanceOf(RegistrationRejected.class);
        }

        @Test
        @DisplayName("and a refusal writes nothing, to the register or the ledger")
        void refusalsLeaveNoTrace() {
            onboard.onboard(command("Example University", "PR-0142"));
            int after = institutions.rows.size();

            assertThatThrownBy(() -> onboard.onboard(command("Another", "PR-0142")))
                    .isInstanceOf(RegistrationRejected.class);

            assertThat(institutions.rows).hasSize(after);
            assertThat(ledger.entries()).hasSize(1);
        }
    }

    // ------------------------------------------------------------------ stubs

    private static final class Institutions implements InstitutionRepository {
        private final Map<UUID, Institution> rows = new HashMap<>();

        @Override
        public List<Institution> findAll() {
            return List.copyOf(rows.values());
        }

        @Override
        public Optional<Institution> findById(UUID id) {
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public Optional<Institution> findByProviderNumber(String providerNumber) {
            return rows.values().stream()
                    .filter(row -> row.providerNumber().equals(providerNumber))
                    .findFirst();
        }

        @Override
        public Institution save(Institution institution) {
            rows.put(institution.id(), institution);
            return institution;
        }
    }

}
