package zw.ac.qvs.credential;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import zw.ac.qvs.credential.application.CredentialSearch;
import zw.ac.qvs.credential.application.CredentialSearchRepository;
import zw.ac.qvs.credential.domain.SearchName;
import zw.ac.qvs.support.PostgresIntegrationTest;
import zw.ac.qvs.testsupport.Requirement;

/**
 * FR-03 against the real schema.
 *
 * <p>Worth running here rather than only as a unit test, because most of what could go wrong
 * lives in the database: whether the trigram index is usable, whether folded text actually
 * matches, whether the join to {@code institution} scopes correctly, and whether paging is
 * stable across two queries that share an award date.
 */
@Requirement("FR-03")
class CredentialSearchIT extends PostgresIntegrationTest {

    private static final UUID EXAMPLE_UNIVERSITY = UUID.randomUUID();
    private static final UUID CAPE_INSTITUTE = UUID.randomUUID();
    private static final UUID BSC = UUID.randomUUID();
    private static final UUID BENG = UUID.randomUUID();

    @Autowired
    private CredentialSearchRepository search;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        // Deliberately NOT truncating signing_key or institution. This used to, and it broke
        // tests that run after it in a way that took a while to see: the key vault holds
        // private keys as files and refuses to overwrite one -- correctly, since overwriting a
        // key invalidates every signature already made with it -- so wiping the database's key
        // rows left the files behind, and the next test to rotate a key for the same
        // institution and date derived the same identifier and was refused. The database and
        // the vault have to agree, and only the vault can say no.
        //
        // Nothing here needs those tables cleared anyway: the fixtures below use freshly
        // generated institution identifiers, so they cannot collide with another test's. They
        // do now outlive a single test method, which is why the two inserts are idempotent --
        // the institutions and keys are set up once and the credentials are rebuilt per test.
        jdbc.execute("TRUNCATE credential, holder, qualification CASCADE");

        institution(EXAMPLE_UNIVERSITY, "Example University", "PR-A");
        institution(CAPE_INSTITUTE, "Harare Institute of Technology", "PR-B");
        // A credential references the key that signed it, so the fixture needs one even though
        // nothing here verifies a signature.
        signingKey("kid-a", EXAMPLE_UNIVERSITY);
        signingKey("kid-b", CAPE_INSTITUTE);
        qualification(BSC, EXAMPLE_UNIVERSITY, "BSc Computer Science", 7);
        qualification(BENG, CAPE_INSTITUTE, "BEng Mechanical Engineering", 8);

        // Two share an award date, so the stable-ordering assertion has something to bite on.
        credential("ZW-PRA-2026-000001", BSC, "Thandeka N. Mahlangu", LocalDate.of(2026, 4, 11));
        credential("ZW-PRA-2026-000002", BSC, "Sibusiso Ndlovu", LocalDate.of(2026, 4, 11));
        credential("ZW-PRA-2025-000001", BSC, "Renée Botha", LocalDate.of(2025, 12, 5));
        credential("ZW-PRB-2026-000001", BENG, "Farai Ncube", LocalDate.of(2026, 6, 30));
    }

    private void institution(UUID id, String name, String providerNo) {
        jdbc.update("""
                INSERT INTO institution (id, name, country, provider_no, accredited_until)
                VALUES (?, ?, 'ZW', ?, DATE '2030-12-31')
                ON CONFLICT (id) DO NOTHING
                """, id, name, providerNo);
    }

    private void signingKey(String kid, UUID institutionId) {
        jdbc.update("""
                INSERT INTO signing_key (kid, institution_id, public_key, valid_from)
                VALUES (?, ?, '{"kty":"OKP"}', DATE '2020-01-01')
                ON CONFLICT (kid) DO NOTHING
                """, kid, institutionId);
    }

    private void qualification(UUID id, UUID institutionId, String title, int nqfLevel) {
        jdbc.update("""
                INSERT INTO qualification (id, institution_id, title, nqf_level, credits, status)
                VALUES (?, ?, ?, ?, 360, 'ACTIVE')
                """, id, institutionId, title, (short) nqfLevel);
    }

    private void credential(String serial, UUID qualificationId, String name, LocalDate awarded) {
        String kid = qualificationId.equals(BSC) ? "kid-a" : "kid-b";
        UUID holderId = UUID.randomUUID();
        String idHash = String.format("%064x", Math.abs((long) name.hashCode()));

        jdbc.update("""
                INSERT INTO holder (id, pseudonym_ref, national_id_hash, display_name_enc,
                                    initials, search_name)
                VALUES (?, ?, ?, 'ciphertext', ?, ?)
                """, holderId, "hld_" + holderId, idHash, initialsOf(name),
                SearchName.normalise(name));

        jdbc.update("""
                INSERT INTO credential (serial, qualification_id, holder_id, awarded_on, kid,
                                        detached_jws, payload_canonical, status)
                VALUES (?, ?, ?, ?, ?, 'jws', '{}', 'ISSUED')
                """, serial, qualificationId, holderId, java.sql.Date.valueOf(awarded), kid);
    }

    private static String initialsOf(String name) {
        StringBuilder out = new StringBuilder();
        for (String part : name.trim().split("\\s+")) {
            if (!part.isEmpty() && Character.isLetter(part.charAt(0))) {
                out.append(Character.toUpperCase(part.charAt(0))).append('.');
            }
        }
        return out.toString();
    }

    private static CredentialSearch.Query query(
            String name, UUID institution, Integer nqf, int page, int size) {
        return new CredentialSearch.Query(name, null, institution, nqf, null, null, null,
                page, size);
    }

    @Test
    @DisplayName("finds a holder by part of their name")
    void findsByPartialName() {
        var results = search.search(query("mahlangu", null, null, 0, 20));

        assertThat(results.totalRows()).isEqualTo(1);
        assertThat(results.rows().getFirst().serial()).isEqualTo("ZW-PRA-2026-000001");
    }

    @Test
    @DisplayName("matches across accents and case, because both sides are folded")
    void foldsAccents() {
        // The stored column holds "renee botha"; the caller types the name with its accent.
        var results = search.search(query(SearchName.normalise("Renée"), null, null, 0, 20));

        assertThat(results.totalRows()).isEqualTo(1);
        assertThat(results.rows().getFirst().serial()).isEqualTo("ZW-PRA-2025-000001");
    }

    @Test
    @DisplayName("scopes to one institution when the use case pins it")
    void scopesToInstitution() {
        var mine = search.search(query(null, EXAMPLE_UNIVERSITY, null, 0, 20));
        var theirs = search.search(query(null, CAPE_INSTITUTE, null, 0, 20));

        assertThat(mine.totalRows()).isEqualTo(3);
        assertThat(theirs.totalRows()).isEqualTo(1);
        assertThat(mine.rows()).noneMatch(row -> row.serial().startsWith("ZW-PRB"));
    }

    @Test
    @DisplayName("searches every institution when no scope is applied")
    void unscopedSeesEverything() {
        assertThat(search.search(query(null, null, null, 0, 20)).totalRows()).isEqualTo(4);
    }

    @Test
    @DisplayName("narrows by NQF level")
    void filtersByNqfLevel() {
        var level8 = search.search(query(null, null, 8, 0, 20));

        assertThat(level8.totalRows()).isEqualTo(1);
        assertThat(level8.rows().getFirst().nqfLevel()).isEqualTo(8);
    }

    @Test
    @DisplayName("returns initials, never the stored name")
    void disclosesInitialsOnly() {
        // NFR-06 at the point it matters most. A search screen is exactly where a full-name
        // column would be most convenient and most damaging.
        var results = search.search(query("mahlangu", null, null, 0, 20));

        assertThat(results.rows().getFirst().holderInitials()).isEqualTo("T.N.M.");
        assertThat(results.rows().toString()).doesNotContain("Thandeka");
    }

    @Test
    @DisplayName("orders newest award first, and breaks ties stably")
    void stableOrdering() {
        var results = search.search(query(null, EXAMPLE_UNIVERSITY, null, 0, 20));

        assertThat(results.rows()).extracting(CredentialSearch.Row::serial)
                .containsExactly("ZW-PRA-2026-000001", "ZW-PRA-2026-000002", "ZW-PRA-2025-000001");
    }

    @Test
    @DisplayName("pages without dropping or repeating a row")
    void pagesCleanly() {
        var first = search.search(query(null, EXAMPLE_UNIVERSITY, null, 0, 2));
        var second = search.search(query(null, EXAMPLE_UNIVERSITY, null, 1, 2));

        assertThat(first.rows()).hasSize(2);
        assertThat(first.hasMore()).isTrue();
        assertThat(second.rows()).hasSize(1);
        assertThat(second.hasMore()).isFalse();
        assertThat(first.totalRows()).isEqualTo(3);
        assertThat(first.rows().getFirst().serial())
                .isNotEqualTo(second.rows().getFirst().serial());
    }

    @Test
    @DisplayName("a search matching nothing is an empty page, not a failure")
    void noMatches() {
        var results = search.search(query("nobodyhasthisname", null, null, 0, 20));

        assertThat(results.rows()).isEmpty();
        assertThat(results.totalRows()).isZero();
        assertThat(results.totalPages()).isZero();
    }

    @Test
    @DisplayName("a wildcard typed into the name box matches literally, not everything")
    void likeWildcardsAreEscaped() {
        // Without escaping, "%" in the search box returns the whole register — which looks
        // like a feature until somebody notices it ignores every other filter too.
        var results = search.search(query("%", null, null, 0, 20));

        assertThat(results.totalRows()).isZero();
    }
}
