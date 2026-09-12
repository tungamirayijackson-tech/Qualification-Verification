package zw.ac.qvs.identity.domain;

/**
 * The four powers in the system (S01, FR-10).
 *
 * <p>Written as a closed set because the trust model depends on it. Each role can assert
 * something different, and the interesting column is what each one may <em>never</em> do.
 */
public enum Role {

    /**
     * Records and revokes on behalf of exactly one institution.
     *
     * <p>May never act for an institution they are not bound to. Enforced by an institution
     * scope on the user row and a filter in the repository layer, not by remembering to check.
     */
    REGISTRAR,

    /**
     * Asks. Asserts nothing.
     *
     * <p>May never enumerate or browse the register: there is no endpoint that lists
     * credentials, for this role or any other unauthenticated caller.
     */
    VERIFIER,

    /**
     * Reads the ledger across institutions and says whether it is intact.
     *
     * <p>May never write anything at all — not a credential, not a revocation, and above all
     * not a ledger entry. An auditor who could append to the history they audit would be able
     * to cover their own tracks.
     */
    AUDITOR,

    /**
     * Onboards institutions and rotates keys.
     *
     * <p>The most dangerous role, and the reason MFA is mandatory for it.
     */
    ADMIN;

    /** Spring Security expects authorities to be prefixed. */
    public String authority() {
        return "ROLE_" + name();
    }

    /**
     * Whether this role must enrol in MFA before it can be used.
     *
     * <p>The two roles that can change what the register says. A leaked password for either
     * of them is a forged credential; for the other two it is a read of data they were already
     * entitled to read.
     *
     * @return true for REGISTRAR and ADMIN
     */
    public boolean requiresMfa() {
        return this == REGISTRAR || this == ADMIN;
    }

    /**
     * Whether this role is bound to a single institution.
     *
     * @return true for REGISTRAR
     */
    public boolean isInstitutionScoped() {
        return this == REGISTRAR;
    }
}
