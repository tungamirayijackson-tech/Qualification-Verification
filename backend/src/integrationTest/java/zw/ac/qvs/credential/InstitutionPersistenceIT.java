package zw.ac.qvs.credential;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import zw.ac.qvs.credential.application.InstitutionRepository;
import zw.ac.qvs.support.PostgresIntegrationTest;

/**
 * The walking skeleton's proof that the outbound adapter and the real schema agree.
 *
 * <p>The negative cases matter more than the positive one. A CHECK constraint nobody tests is
 * a constraint that gets dropped in a later migration without anyone noticing.
 */
class InstitutionPersistenceIT extends PostgresIntegrationTest {

    @Autowired
    private InstitutionRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * Every provider number this class uses, so its rows can be found and removed again.
     *
     * <p>This used to be {@code TRUNCATE institution CASCADE}, which was quietly destructive:
     * the cascade reaches {@code signing_key}, and the key vault keeps private keys as files
     * it refuses to overwrite. Deleting the database's key rows therefore left the files
     * orphaned, and the next test to rotate a key for the same institution and date derived
     * the same identifier and was refused a key it had every right to expect. Clearing only
     * this class's own rows keeps the failure impossible rather than merely unlikely.
     */
    private static final String PROVIDER_PREFIX = "PR-IPI-";

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM institution WHERE provider_no LIKE ?", PROVIDER_PREFIX + "%");
    }

    private void insert(UUID id, String name, String country, String providerNo, LocalDate until) {
        jdbc.update(
                "INSERT INTO institution (id, name, country, provider_no, accredited_until) "
                        + "VALUES (?, ?, ?, ?, ?)",
                id, name, country, providerNo, until);
    }

    @Test
    @DisplayName("round-trips a row into the domain record, ordered by name")
    void roundTrip() {
        insert(UUID.randomUUID(), "Zenith College", "ZW", PROVIDER_PREFIX + "002", LocalDate.of(2030, 1, 1));
        insert(UUID.randomUUID(), "Apex University", "ZW", PROVIDER_PREFIX + "001", LocalDate.of(2029, 6, 30));

        var found = repository.findAll();

        // containsSubsequence, not containsExactly: this asserts the ordering the repository
        // promises without also asserting that this test owns the whole table. The exhaustive
        // form only held because the fixture wiped every institution first, which is what made
        // it destructive to its neighbours.
        assertThat(found).extracting("name")
                .containsSubsequence("Apex University", "Zenith College");
        assertThat(found.getFirst().wasAccreditedOn(LocalDate.of(2029, 6, 30))).isTrue();
    }

    @Test
    @DisplayName("the database refuses a duplicate provider number")
    void providerNumberIsUnique() {
        insert(UUID.randomUUID(), "First", "ZW", PROVIDER_PREFIX + "DUP", LocalDate.of(2030, 1, 1));

        assertThatThrownBy(() ->
                insert(UUID.randomUUID(), "Second", "ZW", PROVIDER_PREFIX + "DUP", LocalDate.of(2030, 1, 1)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("the database refuses a country code that is not ISO alpha-2 upper case")
    void countryCodeIsChecked() {
        assertThatThrownBy(() ->
                insert(UUID.randomUUID(), "Bad", "za", PROVIDER_PREFIX + "LOW", LocalDate.of(2030, 1, 1)))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() ->
                insert(UUID.randomUUID(), "Bad", "ZAF", PROVIDER_PREFIX + "LONG", LocalDate.of(2030, 1, 1)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("an unknown id is empty rather than an exception")
    void unknownId() {
        assertThat(repository.findById(UUID.randomUUID())).isEmpty();
    }
}
