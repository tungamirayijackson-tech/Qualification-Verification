package zw.ac.qvs.credential.adapter.in;

import java.util.UUID;
import zw.ac.qvs.credential.domain.Qualification;

/**
 * A qualification, as the console sees it.
 *
 * @param id            the identity a registrar quotes when registering an award
 * @param institutionId which institution offers it
 * @param title         the registered title
 * @param nqfLevel      NQF level
 * @param credits       credit value
 * @param saqaQualId    SAQA registration id, when it has one
 * @param phasedOut     whether it still accepts new awards
 */
public record QualificationResponse(
        UUID id,
        UUID institutionId,
        String title,
        int nqfLevel,
        int credits,
        String saqaQualId,
        boolean phasedOut) {

    static QualificationResponse from(Qualification qualification) {
        return new QualificationResponse(
                qualification.id(),
                qualification.institutionId(),
                qualification.title(),
                qualification.nqfLevel().value(),
                qualification.credits(),
                qualification.saqaQualId(),
                qualification.phasedOut());
    }
}
