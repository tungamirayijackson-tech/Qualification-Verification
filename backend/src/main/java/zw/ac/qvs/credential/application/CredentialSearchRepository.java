package zw.ac.qvs.credential.application;

/**
 * Outbound port for FR-03.
 *
 * <p>One method, taking a fully-resolved query. By the time a query reaches here its scope has
 * already been decided and its terms already folded, so an implementation has no authorisation
 * decisions left to make and cannot get one wrong.
 */
public interface CredentialSearchRepository {

    /**
     * Finds credentials matching a query.
     *
     * <p>Implementations must treat a null criterion as "do not narrow on this", and must apply
     * {@code institutionId} exactly as given — including honouring null as "every institution",
     * which only a cross-institution role can produce.
     *
     * @param query the resolved query
     * @return a page of matches, newest award first
     */
    CredentialSearch.Page search(CredentialSearch.Query query);
}
