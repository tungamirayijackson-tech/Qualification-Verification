package zw.ac.qvs.ledger.domain;

/**
 * Every kind of event the ledger records.
 *
 * <p>Mirrors the CHECK constraint on {@code audit_entry.action}. The duplication is deliberate:
 * an unknown action must be refused by the database as well as by the type system, because the
 * ledger's value rests on nothing reaching it that was not intended to.
 *
 * <p>Note that verifications are recorded, not just state changes. "Maintain an auditable
 * history of verification activities" is a requirement in its own right, and it is the part
 * that lets an institution later prove who confirmed what, on which date.
 */
public enum LedgerAction {

    CREDENTIAL_ISSUED,
    CREDENTIAL_REVOKED,
    CREDENTIAL_IMPORTED,

    VERIFICATION_RUN,
    SHARE_TOKEN_ISSUED,
    SHARE_TOKEN_REVOKED,

    TRAIL_EXPORTED,
    CHAIN_VERIFIED,

    INSTITUTION_ONBOARDED,
    KEY_ROTATED,

    /**
     * A qualification was recorded against an institution.
     *
     * <p>Reference data, and audited anyway: a qualification is the precondition for issuing
     * credentials against it, so adding one decides what this register is capable of attesting
     * to. That is closer to onboarding an institution than to editing a lookup table.
     */
    QUALIFICATION_ADDED,

    /**
     * An account was created — by an administrator, or by the first-run bootstrap.
     *
     * <p>Audited because creating a user is how every other power in this system is handed
     * out. An unexplained REGISTRAR is a person who can sign credentials on an institution's
     * behalf, and the ledger is where the question "who let them in" is answered.
     */
    USER_CREATED,

    /**
     * An account was suspended, so it can no longer be signed in to.
     *
     * <p>The counterpart of {@link #USER_CREATED}, and audited for the same reason: withdrawing
     * someone's access is a change to who may act on this system's behalf. It is also the entry
     * that answers the question after an incident — whether the account was still usable at the
     * time something was signed with it.
     */
    USER_DISABLED,

    /** A suspended account was given its access back. */
    USER_RESTORED,

    /**
     * An administrator issued a new password for an account.
     *
     * <p>The password itself appears in no entry, no log and no export. What is recorded is
     * that a reset happened, to which account, and by whom — enough to notice a password being
     * reset by somebody who had no business resetting it, which is what an attacker taking over
     * an account through the support path looks like from here.
     */
    USER_PASSWORD_RESET,

    /**
     * An administrator cleared an account's second factor.
     *
     * <p>Recorded because it briefly lowers an account to one factor: until the owner enrols
     * again, a password alone reaches the enrolment step. That is the intended answer to a lost
     * phone, and it is exactly the step an attacker would want, so it is written down.
     */
    USER_MFA_RESET,

    USER_LOGGED_IN,
    USER_LOGIN_FAILED,
    REPORT_GENERATED
}
