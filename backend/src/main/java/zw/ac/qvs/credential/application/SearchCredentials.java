package zw.ac.qvs.credential.application;

import java.util.UUID;
import zw.ac.qvs.credential.domain.NationalId;
import zw.ac.qvs.credential.domain.SearchName;
import zw.ac.qvs.shared.domain.CurrentActor;
import zw.ac.qvs.shared.domain.Hashing;

/**
 * FR-03: find qualification records.
 *
 * <p>The whole point of this class is the scoping rule, and it is worth stating plainly: a
 * registrar's search is pinned to their own institution, and there is no request they can
 * construct that widens it. The institution is taken from their signed token and written over
 * whatever the query said, before the query reaches the database. An auditor is deliberately
 * cross-institution and may narrow to one if they choose.
 *
 * <p>Doing it here rather than in the SQL builder is intentional. Scope is an authorisation
 * decision, and authorisation decisions belong somewhere a reader can find them — not folded
 * into a WHERE clause where the absence of a condition is invisible.
 */
public class SearchCredentials {

    private final CredentialSearchRepository repository;
    private final String nationalIdSalt;

    public SearchCredentials(CredentialSearchRepository repository, String nationalIdSalt) {
        this.repository = repository;
        this.nationalIdSalt = nationalIdSalt;
    }

    /** Raised when a caller is not scoped to anything they could search. */
    public static class NotScopedToAnInstitution extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public NotScopedToAnInstitution() {
            super("This account is not bound to an institution, so it cannot search the register.");
        }
    }

    /**
     * Runs a search on behalf of an actor.
     *
     * @param query what was asked
     * @param actor who is asking
     * @return a page of matches
     */
    public CredentialSearch.Page search(CredentialSearch.Query query, CurrentActor actor) {
        CredentialSearch.Query scoped = applyScope(query, actor);
        CredentialSearch.Query folded = foldSearchTerms(scoped);

        return repository.search(folded);
    }

    /**
     * Hashes a national ID for lookup.
     *
     * <p>Offered as a separate step so a caller can search by identity without the plaintext ID
     * ever reaching a query parameter, a proxy log or a browser history entry.
     *
     * @param nationalId the plaintext identifier
     * @return the salted hash the register stores
     */
    public String hashNationalId(String nationalId) {
        // Through NationalId, so that a search and a registration hash the same person the same
        // way whatever punctuation each of them was typed with. This used to trim its input and
        // registration did not, which was already a difference and would have become a bug the
        // moment the format allowed a hyphen.
        return Hashing.sha256Hex(nationalIdSalt + NationalId.parse(nationalId).canonical());
    }

    private CredentialSearch.Query applyScope(CredentialSearch.Query query, CurrentActor actor) {
        UUID ownInstitution = actor.institutionId();

        if (ownInstitution != null) {
            // A scoped role. Overwrite rather than validate: rejecting a mismatched institution
            // would confirm that the other institution exists, and there is nothing useful the
            // caller could do with the answer either way.
            return query.scopedTo(ownInstitution);
        }

        if ("AUDITOR".equals(actor.role()) || "ADMIN".equals(actor.role())) {
            // Cross-institution by design. Whatever they asked for stands, including null.
            return query;
        }

        throw new NotScopedToAnInstitution();
    }

    /**
     * Folds the name term the same way the stored column was folded, and drops it when it is
     * too short to be worth a trigram scan.
     */
    private CredentialSearch.Query foldSearchTerms(CredentialSearch.Query query) {
        if (query.holderName() == null) {
            return query;
        }
        String folded = SearchName.normalise(query.holderName());
        String usable = SearchName.isSearchable(folded) ? folded : null;

        return new CredentialSearch.Query(usable, query.nationalIdHash(), query.institutionId(),
                query.nqfLevel(), query.status(), query.awardedFrom(), query.awardedTo(),
                query.page(), query.size());
    }
}
