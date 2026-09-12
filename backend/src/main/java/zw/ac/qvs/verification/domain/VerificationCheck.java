package zw.ac.qvs.verification.domain;

/**
 * The four independent checks a verdict is the conjunction of (S05).
 *
 * <p>"Verify the authenticity of a qualification" is most often implemented as a database
 * lookup returning true. That is not verification; it is a claim that the server's own table
 * is correct. Each of these asks a different question, and each can fail on its own.
 */
public enum VerificationCheck {

    /**
     * The detached signature verifies against the institution's published key.
     *
     * <p>Change one character of the holder name or the NQF level and this fails. It is the
     * only check that detects an edit made directly in the database.
     */
    SIGNATURE,

    /**
     * The signing key was valid, and the institution accredited, <em>on the award date</em>.
     *
     * <p>Not "accredited today". A 2020 degree from an institution whose accreditation lapsed
     * in 2024 is still a real degree.
     */
    ISSUER_STANDING,

    /** The credential has not been withdrawn, locally or on the external feed. */
    REVOCATION,

    /**
     * The issuance is present in the audit chain and the chain recomputes.
     *
     * <p>If someone inserted a credential row directly, this is the check that notices: they
     * would also have to forge a chain entry whose hash links to its neighbours.
     */
    LEDGER_PRESENCE
}
