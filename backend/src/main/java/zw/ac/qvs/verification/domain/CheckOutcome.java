package zw.ac.qvs.verification.domain;

/** Result of a single {@link VerificationCheck}. */
public enum CheckOutcome {

    /** The check ran and was satisfied. */
    PASS,

    /** The check ran and was not satisfied. */
    FAIL,

    /**
     * The check did not run, because an earlier one had already decided the verdict.
     *
     * <p>Reported honestly rather than shown as a pass. A verifier reading four green ticks
     * should be able to trust that four checks actually ran.
     */
    NOT_RUN
}
