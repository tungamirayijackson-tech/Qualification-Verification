package zw.ac.qvs.credential.application;

import java.time.LocalDate;
import java.util.Locale;
import java.util.UUID;
import zw.ac.qvs.credential.domain.Institution;
import zw.ac.qvs.ledger.application.AppendEntry;
import zw.ac.qvs.ledger.domain.LedgerAction;

/**
 * Admits an awarding body to the register.
 *
 * <p>The most consequential thing an administrator can do. Every credential this system will
 * ever attest to is signed by an institution that was let in here, and a verifier trusts a
 * verdict because they trust the register's idea of who is a university. So the act is audited,
 * refuses duplicates by provider number, and — deliberately — does <b>not</b> give the new
 * institution a signing key.
 *
 * <p>That last point is the design decision worth defending. Onboarding and key issuance are
 * separate acts because they answer different questions: "is this a real awarding body" and
 * "may it start signing today". An institution admitted without a key appears in the register,
 * can have its qualifications recorded, and cannot issue anything — which is exactly the state
 * a half-finished onboarding should leave behind. Doing both at once would mean a mistyped
 * provider number produces a key that must then be explained away, and the vault refuses to
 * delete keys.
 */
public class OnboardInstitution {

    private final InstitutionRepository institutions;
    private final AppendEntry ledger;

    public OnboardInstitution(InstitutionRepository institutions, AppendEntry ledger) {
        this.institutions = institutions;
        this.ledger = ledger;
    }

    /**
     * What an administrator supplies.
     *
     * @param name             registered name of the institution
     * @param country          ISO 3166-1 alpha-2 country code
     * @param providerNumber   national provider registration number
     * @param accreditedUntil  last date the accreditation is valid, inclusive
     * @param actorId          the administrator doing it, recorded in the ledger
     */
    public record Command(
            String name,
            String country,
            String providerNumber,
            LocalDate accreditedUntil,
            UUID actorId) {
    }

    /**
     * Admits an institution.
     *
     * @param command what to record
     * @return the stored institution, with the identity the register assigned it
     * @throws RegistrationRejected when the provider number is already held by another
     *                              institution, or the accreditation has already lapsed
     */
    public Institution onboard(Command command) {
        String providerNumber = normalised(command.providerNumber());

        institutions.findByProviderNumber(providerNumber).ifPresent(existing -> {
            throw new RegistrationRejected(RegistrationRejected.Reason.DUPLICATE_PROVIDER_NUMBER,
                    "Provider number " + providerNumber + " already belongs to "
                            + existing.name() + ". A provider number identifies one institution, "
                            + "so this is either a duplicate or a typo.");
        });

        // Refused rather than warned about. An institution admitted with an accreditation that
        // has already lapsed cannot issue anything, so recording one is a mistake being stored
        // rather than a state anybody wanted.
        if (command.accreditedUntil() != null
                && command.accreditedUntil().isBefore(LocalDate.now())) {
            throw new RegistrationRejected(
                    RegistrationRejected.Reason.ACCREDITATION_ALREADY_LAPSED,
                    "Accreditation ended on " + command.accreditedUntil()
                            + ". An institution cannot be admitted with an accreditation that "
                            + "has already lapsed.");
        }

        Institution institution = new Institution(
                UUID.randomUUID(),
                command.name() == null ? null : command.name().trim(),
                command.country() == null ? null : command.country().toUpperCase(Locale.ROOT),
                providerNumber,
                command.accreditedUntil(),
                // No key. Issuing one is a separate, separately audited act.
                null);

        Institution stored = institutions.save(institution);

        ledger.record(command.actorId(), "ADMIN", LedgerAction.INSTITUTION_ONBOARDED,
                stored.id().toString(),
                stored.name() + "|" + stored.providerNumber() + "|" + stored.accreditedUntil());

        return stored;
    }

    private static String normalised(String providerNumber) {
        if (providerNumber == null || providerNumber.isBlank()) {
            throw new RegistrationRejected(RegistrationRejected.Reason.UNKNOWN_REFERENCE,
                    "A provider registration number is required.");
        }
        return providerNumber.trim().toUpperCase(Locale.ROOT);
    }
}
