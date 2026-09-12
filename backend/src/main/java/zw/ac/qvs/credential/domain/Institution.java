package zw.ac.qvs.credential.domain;

import java.time.LocalDate;
import java.util.UUID;

/**
 * An awarding body, as the register knows it.
 *
 * <p>Pure domain: no Spring, no JPA. Accreditation is a point-in-time question rather than a
 * boolean flag, because FR-05 requires verifying that the issuer had standing <em>on the award
 * date</em> and not merely that it has standing today.
 *
 * @param id              surrogate identity
 * @param name            registered name of the institution
 * @param country         ISO 3166-1 alpha-2 country code
 * @param providerNumber  national provider registration number
 * @param accreditedUntil last date on which the accreditation is valid, inclusive
 * @param activeKeyId     key id currently used to sign issuances, may be null before onboarding
 */
public record Institution(
        UUID id,
        String name,
        String country,
        String providerNumber,
        LocalDate accreditedUntil,
        String activeKeyId) {

    public Institution {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("institution name is required");
        }
        if (country == null || country.length() != 2) {
            throw new IllegalArgumentException("country must be an ISO 3166-1 alpha-2 code");
        }
        if (accreditedUntil == null) {
            throw new IllegalArgumentException("accreditedUntil is required");
        }
    }

    /**
     * Whether this institution was accredited on a given date.
     *
     * @param on the date to test, typically a credential's award date
     * @return true when the accreditation had not yet lapsed on that date
     */
    public boolean wasAccreditedOn(LocalDate on) {
        if (on == null) {
            throw new IllegalArgumentException("date is required");
        }
        return !on.isAfter(accreditedUntil);
    }

    /**
     * Whether this institution may register new credentials as at the given date.
     *
     * @param today the current date, injected rather than read from the system clock
     * @return true when accreditation is current and a signing key is configured
     */
    public boolean canIssueOn(LocalDate today) {
        return wasAccreditedOn(today) && activeKeyId != null && !activeKeyId.isBlank();
    }
}
