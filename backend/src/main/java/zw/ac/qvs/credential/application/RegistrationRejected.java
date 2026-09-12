package zw.ac.qvs.credential.application;

/**
 * A registration the system refuses to record.
 *
 * <p>Carries a stable machine-readable reason as well as a message, so the console can explain
 * the refusal without matching on English text and the API can map it to an RFC 9457 problem
 * type. FR-01 requires a lapsed institution to be refused with 422 rather than 400: the
 * request was well-formed, the register simply will not accept it.
 */
public class RegistrationRejected extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Why a registration was refused. */
    public enum Reason {

        /** The institution's accreditation had lapsed on the award date. */
        INSTITUTION_NOT_ACCREDITED,

        /** The institution has no signing key, so nothing could vouch for the award. */
        INSTITUTION_HAS_NO_KEY,

        /** The qualification has been phased out and accepts no new awards. */
        QUALIFICATION_PHASED_OUT,

        /** The qualification is not offered by the institution named. */
        QUALIFICATION_NOT_OFFERED_HERE,

        /** The named institution or qualification does not exist. */
        UNKNOWN_REFERENCE,

        /** This holder already holds this qualification from this institution. */
        DUPLICATE_AWARD,

        /** The award date is in the future, or otherwise impossible. */
        IMPOSSIBLE_AWARD_DATE,

        /** Another institution already holds this provider registration number. */
        DUPLICATE_PROVIDER_NUMBER,

        /** The accreditation being recorded had already ended before it was recorded. */
        ACCREDITATION_ALREADY_LAPSED,

        /** A qualification with this title already exists at this institution. */
        DUPLICATE_QUALIFICATION
    }

    private final transient Reason reason;

    public RegistrationRejected(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
