package zw.ac.qvs.credential.adapter.in;

import java.time.LocalDate;
import java.util.UUID;
import zw.ac.qvs.credential.domain.Institution;

/**
 * Wire representation of an institution.
 *
 * @param id              institution identity
 * @param name            registered name
 * @param country         ISO 3166-1 alpha-2 code
 * @param providerNumber  national provider registration number
 * @param accreditedUntil last date the accreditation is valid
 * @param activeKeyId     signing key currently in use, null when not yet onboarded
 */
public record InstitutionResponse(
        UUID id,
        String name,
        String country,
        String providerNumber,
        LocalDate accreditedUntil,
        String activeKeyId) {

    static InstitutionResponse from(Institution institution) {
        return new InstitutionResponse(
                institution.id(),
                institution.name(),
                institution.country(),
                institution.providerNumber(),
                institution.accreditedUntil(),
                institution.activeKeyId());
    }
}
