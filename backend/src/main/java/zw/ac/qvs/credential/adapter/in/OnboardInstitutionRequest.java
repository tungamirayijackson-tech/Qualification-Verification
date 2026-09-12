package zw.ac.qvs.credential.adapter.in;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * What an administrator sends to admit an awarding body.
 *
 * <p>Validated here as well as in the use case, and the duplication is deliberate: this layer
 * refuses what is malformed — a blank name, a three-letter country — so the use case only ever
 * sees plausible input and can spend its refusals on what is actually a domain decision, like a
 * provider number that already belongs to somebody.
 *
 * @param name            registered name of the institution
 * @param country         ISO 3166-1 alpha-2 country code
 * @param providerNumber  national provider registration number
 * @param accreditedUntil last date the accreditation is valid, inclusive
 */
public record OnboardInstitutionRequest(
        @NotBlank(message = "the institution's registered name is required")
        @Size(max = 200, message = "the name is longer than the register allows")
        String name,

        @NotBlank(message = "a country code is required")
        @Pattern(regexp = "^[A-Za-z]{2}$",
                message = "country must be an ISO 3166-1 alpha-2 code, such as ZW")
        String country,

        @NotBlank(message = "a provider registration number is required")
        @Size(max = 32, message = "the provider number is longer than the register allows")
        String providerNumber,

        @NotNull(message = "an accreditation end date is required")
        @Future(message = "the accreditation must not have already ended")
        LocalDate accreditedUntil) {
}
