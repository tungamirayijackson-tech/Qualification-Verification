package zw.ac.qvs.credential.domain;

/**
 * Why a credential was withdrawn.
 *
 * <p>A closed set rather than free text, for two reasons. A verifier needs a reason code they
 * can act on without reading prose, and an aggregate count of revocations by reason is
 * something an institution can be asked about. Free text would give neither.
 *
 * <p>Note what revocation is <em>not</em>: it is not a statement that the signature was
 * invalid. The institution did award the qualification and later withdrew it, and both facts
 * remain true. FR-07 requires the signature to keep verifying after revocation.
 */
public enum RevocationReason {

    /** A clerical mistake in the record itself. */
    ADMINISTRATIVE_ERROR,

    /** The award was withdrawn following a misconduct finding. */
    ACADEMIC_MISCONDUCT,

    /** The qualification itself was withdrawn by the institution or the regulator. */
    QUALIFICATION_WITHDRAWN,

    /** The credential should never have been issued to this holder. */
    ISSUED_IN_ERROR,

    /** The holder asked for it to be withdrawn. */
    HOLDER_REQUEST,

    /** Anything else; requires a note in the audit entry. */
    OTHER
}
