package zw.ac.qvs.credential.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zw.ac.qvs.credential.domain.Institution;

class ListInstitutionsTest {

    private static final Clock FIXED =
            Clock.fixed(Instant.parse("2026-09-05T00:00:00Z"), ZoneOffset.UTC);

    private static Institution institution(String name, LocalDate until, String keyId) {
        return new Institution(UUID.randomUUID(), name, "ZW", "PR-" + name.hashCode(), until, keyId);
    }

    /** In-memory stand-in for the outbound port -- no mocking framework needed. */
    private record StubRepository(List<Institution> rows) implements InstitutionRepository {
        @Override
        public List<Institution> findAll() {
            return rows;
        }

        @Override
        public Optional<Institution> findById(UUID id) {
            return rows.stream().filter(row -> row.id().equals(id)).findFirst();
        }

        // This class only reads, so the write half of the port is refused rather than
        // implemented. A stub that silently accepts a call nobody expects is a stub that
        // makes a test pass while the code under test does something surprising.
        @Override
        public Optional<Institution> findByProviderNumber(String providerNumber) {
            throw new UnsupportedOperationException("ListInstitutions does not look up by number");
        }

        @Override
        public Institution save(Institution institution) {
            throw new UnsupportedOperationException("ListInstitutions does not write");
        }
    }

    @Test
    @DisplayName("all() returns what the register holds, untouched")
    void listsEverything() {
        var rows = List.of(
                institution("Example University", LocalDate.of(2030, 12, 31), "k1"),
                institution("Lapsed College", LocalDate.of(2024, 1, 31), null));

        assertThat(new ListInstitutions(new StubRepository(rows), FIXED).all()).hasSize(2);
    }

    @Test
    @DisplayName("eligibleToIssue() drops lapsed accreditation and missing keys")
    void filtersIneligible() {
        var current = institution("Example University", LocalDate.of(2030, 12, 31), "k1");
        var lapsed = institution("Lapsed College", LocalDate.of(2024, 1, 31), "k2");
        var keyless = institution("Not Onboarded", LocalDate.of(2030, 12, 31), null);

        var eligible = new ListInstitutions(
                new StubRepository(List.of(current, lapsed, keyless)), FIXED).eligibleToIssue();

        assertThat(eligible).containsExactly(current);
    }

    @Test
    @DisplayName("an empty register is an empty list, not a failure")
    void emptyRegister() {
        assertThat(new ListInstitutions(new StubRepository(List.of()), FIXED).eligibleToIssue())
                .isEmpty();
    }
}
