package zw.ac.qvs.credential.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import zw.ac.qvs.shared.domain.CurrentActor;
import zw.ac.qvs.shared.domain.Hashing;
import zw.ac.qvs.testsupport.Requirement;

/**
 * FR-03, with the scoping rule as the headline.
 *
 * <p>A registrar must not be able to widen their own search, however the request is
 * constructed. These tests capture the query the repository actually receives, because that is
 * the only place the guarantee is real — anything asserted about the response would still pass
 * if the scope had been applied in the wrong direction.
 */
@Requirement("FR-03")
class SearchCredentialsTest {

    private static final UUID OWN = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID SOMEBODY_ELSE =
            UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final String SALT = "test-salt";

    /** Records the query it was handed, so the test can assert on what reached the database. */
    private static final class CapturingRepository implements CredentialSearchRepository {
        private CredentialSearch.Query received;

        @Override
        public CredentialSearch.Page search(CredentialSearch.Query query) {
            this.received = query;
            return new CredentialSearch.Page(List.of(), query.page(), query.size(), 0);
        }
    }

    private CapturingRepository repository;
    private SearchCredentials search;

    private static CredentialSearch.Query queryFor(UUID institution) {
        return new CredentialSearch.Query("Thandeka", null, institution, null, null,
                null, null, 0, 20);
    }

    @BeforeEach
    void setUp() {
        repository = new CapturingRepository();
        search = new SearchCredentials(repository, SALT);
    }

    @Nested
    @DisplayName("scope")
    class Scope {

        @Test
        @DisplayName("a registrar is pinned to their own institution")
        void registrarIsPinned() {
            search.search(queryFor(null), new CurrentActor(UUID.randomUUID(), "REGISTRAR", OWN));

            assertThat(repository.received.institutionId()).isEqualTo(OWN);
        }

        @Test
        @DisplayName("a registrar naming another institution is silently pinned back to their own")
        void registrarCannotWidenScope() {
            // The attack this blocks: edit the institution in the request and read somebody
            // else's register. Overwriting rather than rejecting is deliberate -- a rejection
            // would confirm that the other institution exists.
            search.search(queryFor(SOMEBODY_ELSE),
                    new CurrentActor(UUID.randomUUID(), "REGISTRAR", OWN));

            assertThat(repository.received.institutionId()).isEqualTo(OWN);
        }

        @Test
        @DisplayName("an auditor searches every institution by default")
        void auditorIsUnscoped() {
            search.search(queryFor(null), new CurrentActor(UUID.randomUUID(), "AUDITOR", null));

            assertThat(repository.received.institutionId()).isNull();
        }

        @Test
        @DisplayName("an auditor may narrow to one institution if they choose")
        void auditorMayNarrow() {
            search.search(queryFor(SOMEBODY_ELSE),
                    new CurrentActor(UUID.randomUUID(), "AUDITOR", null));

            assertThat(repository.received.institutionId()).isEqualTo(SOMEBODY_ELSE);
        }

        @Test
        @DisplayName("an unscoped role that is not cross-institution cannot search at all")
        void verifierCannotSearch() {
            // A verifier asks about one credential with a token the holder gave them. There is
            // no version of this system in which they browse the register.
            assertThatThrownBy(() -> search.search(queryFor(null),
                    new CurrentActor(UUID.randomUUID(), "VERIFIER", null)))
                    .isInstanceOf(SearchCredentials.NotScopedToAnInstitution.class);

            assertThatThrownBy(() -> search.search(queryFor(null), CurrentActor.anonymous()))
                    .isInstanceOf(SearchCredentials.NotScopedToAnInstitution.class);
        }
    }

    @Nested
    @DisplayName("name terms")
    class NameTerms {

        @Test
        @DisplayName("are folded the same way the stored column was")
        void foldsTheTerm() {
            // If the two ever diverge the search silently stops finding people, and an empty
            // result looks exactly like nobody holding that qualification.
            search.search(
                    new CredentialSearch.Query("Renée  N. MAHLANGU", null, null, null, null,
                            null, null, 0, 20),
                    new CurrentActor(UUID.randomUUID(), "AUDITOR", null));

            assertThat(repository.received.holderName()).isEqualTo("renee n mahlangu");
        }

        @Test
        @DisplayName("a term too short to be worth scanning is dropped rather than run")
        void dropsTinyTerms() {
            search.search(
                    new CredentialSearch.Query("a", null, null, null, null, null, null, 0, 20),
                    new CurrentActor(UUID.randomUUID(), "AUDITOR", null));

            assertThat(repository.received.holderName()).isNull();
        }

        @Test
        @DisplayName("a term of only punctuation folds away to nothing")
        void dropsPunctuationOnlyTerms() {
            search.search(
                    new CredentialSearch.Query("...", null, null, null, null, null, null, 0, 20),
                    new CurrentActor(UUID.randomUUID(), "AUDITOR", null));

            assertThat(repository.received.holderName()).isNull();
        }
    }

    @Test
    @DisplayName("a national ID is hashed with the salt before it can reach a query")
    void hashesNationalId() {
        String hash = search.hashNationalId("63-1234567K42");

        // The canonical form is what is hashed -- digits and the letter, no punctuation -- so
        // that the number a registrar typed with hyphens and the one a CSV carried without them
        // reach the same row.
        assertThat(hash)
                .isEqualTo(Hashing.sha256Hex(SALT + "631234567K42"))
                .doesNotContain("63-1234567K42");
    }

    @Test
    @DisplayName("and the same person written differently hashes the same")
    void punctuationDoesNotMakeANewPerson() {
        // The failure this prevents is silent: a graduate who is plainly in the register, and a
        // search that says there is nobody by that number.
        assertThat(search.hashNationalId("63 1234567 k 42"))
                .isEqualTo(search.hashNationalId("63-1234567K42"))
                .isEqualTo(search.hashNationalId("631234567K42"));
    }

    @Test
    @DisplayName("hashing refuses an empty identifier rather than hashing the salt alone")
    void refusesEmptyIdentifier() {
        assertThatThrownBy(() -> search.hashNationalId(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> search.hashNationalId("  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Nested
    @DisplayName("query validation")
    class QueryValidation {

        @Test
        @DisplayName("clamps an oversized page rather than refusing it")
        void clampsPageSize() {
            var query = new CredentialSearch.Query(null, null, null, null, null, null, null,
                    0, 10_000);

            assertThat(query.size()).isEqualTo(CredentialSearch.MAX_PAGE_SIZE);
        }

        @Test
        @DisplayName("falls back to a default page size")
        void defaultsPageSize() {
            var query = new CredentialSearch.Query(null, null, null, null, null, null, null, 0, 0);

            assertThat(query.size()).isEqualTo(CredentialSearch.DEFAULT_PAGE_SIZE);
        }

        @Test
        @DisplayName("refuses an impossible query")
        void refusesNonsense() {
            assertThatThrownBy(() -> new CredentialSearch.Query(null, null, null, null, null,
                    null, null, -1, 20))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("page");

            assertThatThrownBy(() -> new CredentialSearch.Query(null, null, null, 11, null,
                    null, null, 0, 20))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("NQF");

            assertThatThrownBy(() -> new CredentialSearch.Query(null, null, null, null,
                    "DELETED", null, null, 0, 20))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("ISSUED or REVOKED");

            assertThatThrownBy(() -> new CredentialSearch.Query(null, null, null, null, null,
                    LocalDate.of(2026, 5, 1), LocalDate.of(2026, 4, 1), 0, 20))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("inverted");
        }

        @Test
        @DisplayName("knows whether it narrows anything")
        void detectsAnUnfilteredQuery() {
            var empty = new CredentialSearch.Query(null, null, OWN, null, null, null, null, 0, 20);
            var filtered = new CredentialSearch.Query("thandeka", null, OWN, null, null,
                    null, null, 0, 20);

            assertThat(empty.hasAnyCriterion()).isFalse();
            assertThat(filtered.hasAnyCriterion()).isTrue();
        }
    }

    @Nested
    @DisplayName("paging arithmetic")
    class Paging {

        @Test
        @DisplayName("counts pages and knows when another follows")
        void pageMaths() {
            var firstOfThree = new CredentialSearch.Page(List.of(), 0, 20, 45);

            assertThat(firstOfThree.totalPages()).isEqualTo(3);
            assertThat(firstOfThree.hasMore()).isTrue();

            var lastOfThree = new CredentialSearch.Page(List.of(), 2, 20, 45);
            assertThat(lastOfThree.hasMore()).isFalse();
        }

        @Test
        @DisplayName("an exact multiple does not invent an empty trailing page")
        void exactMultiple() {
            var page = new CredentialSearch.Page(List.of(), 1, 20, 40);

            assertThat(page.totalPages()).isEqualTo(2);
            assertThat(page.hasMore()).isFalse();
        }

        @Test
        @DisplayName("no matches is zero pages, not one empty one")
        void noMatches() {
            var page = new CredentialSearch.Page(List.of(), 0, 20, 0);

            assertThat(page.totalPages()).isZero();
            assertThat(page.hasMore()).isFalse();
        }
    }
}
