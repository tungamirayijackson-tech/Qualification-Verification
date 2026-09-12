package zw.ac.qvs.verification.application;

/**
 * Outbound port for the fourth check: was this issuance actually recorded?
 *
 * <p>Narrow on purpose. The verification module needs one boolean from the ledger and must not
 * acquire the ability to write to it — a module that can both judge credentials and append to
 * the history that judges them is a module that can cover its own tracks.
 */
public interface LedgerPresence {

    /**
     * Whether an issuance entry for this serial exists and is internally consistent.
     *
     * <p>If someone inserted a credential row directly into the database, this is the check
     * that notices: they would also have to forge a ledger entry whose hash links correctly to
     * both of its neighbours.
     *
     * @param serial the credential serial
     * @return true when the issuance is present in the chain
     */
    boolean wasIssuanceRecorded(String serial);
}
