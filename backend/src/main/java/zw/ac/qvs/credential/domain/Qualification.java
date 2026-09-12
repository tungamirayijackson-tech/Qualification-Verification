package zw.ac.qvs.credential.domain;

import java.util.UUID;

/**
 * A qualification an institution offers.
 *
 * @param id             surrogate identity
 * @param institutionId  the awarding body
 * @param title          the qualification's registered title
 * @param nqfLevel       NQF level, 1..10
 * @param credits        credit value, positive
 * @param saqaQualId     SAQA registration id, null when not registered
 * @param phasedOut      whether the institution has stopped offering it
 */
public record Qualification(
        UUID id,
        UUID institutionId,
        String title,
        NqfLevel nqfLevel,
        int credits,
        String saqaQualId,
        boolean phasedOut) {

    public Qualification {
        if (institutionId == null) {
            throw new IllegalArgumentException("qualification must belong to an institution");
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("qualification title is required");
        }
        if (nqfLevel == null) {
            throw new IllegalArgumentException("NQF level is required");
        }
        if (credits <= 0) {
            throw new IllegalArgumentException("credits must be positive, got " + credits);
        }
    }

    /**
     * Whether new credentials may be registered against this qualification.
     *
     * <p>Phasing out stops new awards. It does not touch credentials already issued: those
     * remain valid, because the institution did confer them.
     *
     * @return true when the qualification is still active
     */
    public boolean acceptsNewAwards() {
        return !phasedOut;
    }
}
