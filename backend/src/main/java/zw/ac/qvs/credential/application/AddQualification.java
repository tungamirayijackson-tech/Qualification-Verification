package zw.ac.qvs.credential.application;

import java.util.Locale;
import java.util.UUID;
import zw.ac.qvs.credential.domain.NqfLevel;
import zw.ac.qvs.credential.domain.Qualification;
import zw.ac.qvs.ledger.application.AppendEntry;
import zw.ac.qvs.ledger.domain.LedgerAction;

/**
 * Records a qualification an institution offers.
 *
 * <p>Reference data, and audited anyway. A qualification is the precondition for issuing
 * credentials against it, so somebody who can add one can decide what this register is capable
 * of attesting to — which puts it closer to onboarding an institution than to editing a lookup
 * table.
 *
 * <p>Open to a <b>registrar as well as an administrator</b>, and scoped: a registrar may add
 * qualifications only to their own institution. That is the same rule search already follows,
 * and for the same reason — a registrar at one university has no business shaping another's
 * prospectus. An administrator is not institution-scoped and names the institution explicitly.
 */
public class AddQualification {

    private final InstitutionRepository institutions;
    private final QualificationRepository qualifications;
    private final AppendEntry ledger;

    public AddQualification(
            InstitutionRepository institutions,
            QualificationRepository qualifications,
            AppendEntry ledger) {
        this.institutions = institutions;
        this.qualifications = qualifications;
        this.ledger = ledger;
    }

    /**
     * What the caller supplies.
     *
     * @param institutionId which institution offers it
     * @param title         the registered title
     * @param nqfLevel      NQF level, 1..10
     * @param credits       credit value, positive
     * @param saqaQualId    SAQA registration id, optional
     * @param actorId       who is doing it, recorded in the ledger
     * @param actorRole     the role they hold, recorded in the ledger
     */
    public record Command(
            UUID institutionId,
            String title,
            int nqfLevel,
            int credits,
            String saqaQualId,
            UUID actorId,
            String actorRole) {
    }

    /**
     * Records a qualification.
     *
     * @param command what to record
     * @return the stored qualification, with the identity the register assigned it
     * @throws RegistrationRejected when the institution is unknown, or the institution already
     *                              offers a qualification with this title
     */
    public Qualification add(Command command) {
        var institution = institutions.findById(command.institutionId())
                .orElseThrow(() -> new RegistrationRejected(
                        RegistrationRejected.Reason.UNKNOWN_REFERENCE,
                        "No institution with id " + command.institutionId() + " is registered."));

        String title = command.title() == null ? "" : command.title().trim();

        // Two qualifications with the same title at one institution would make the register
        // ambiguous exactly where a registrar has to choose between them.
        boolean alreadyOffered = qualifications.findByInstitution(institution.id()).stream()
                .anyMatch(existing -> existing.title().equalsIgnoreCase(title));

        if (alreadyOffered) {
            throw new RegistrationRejected(
                    RegistrationRejected.Reason.DUPLICATE_QUALIFICATION,
                    institution.name() + " already offers a qualification titled \"" + title
                            + "\".");
        }

        Qualification qualification = new Qualification(
                UUID.randomUUID(),
                institution.id(),
                title,
                new NqfLevel(command.nqfLevel()),
                command.credits(),
                blankToNull(command.saqaQualId()),
                false);

        Qualification stored = qualifications.save(qualification);

        ledger.record(command.actorId(), command.actorRole(), LedgerAction.QUALIFICATION_ADDED,
                stored.id().toString(),
                institution.name() + "|" + stored.title() + "|NQF" + command.nqfLevel()
                        + "|" + stored.credits() + " credits");

        return stored;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
    }
}
