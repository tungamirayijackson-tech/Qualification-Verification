package zw.ac.qvs.credential.adapter.in;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * What a registrar sends to record a qualification.
 *
 * <p>There is deliberately no institution here. Recording a qualification is a registrar's act,
 * and a registrar's institution comes from their own token — so there is no field for one, which
 * is a stronger guarantee than validating a field nobody should be sending. An administrator
 * cannot record one at all: admitting an institution is administration, and deciding what that
 * institution awards is the institution's own business.
 *
 * @param title         the registered title
 * @param nqfLevel      NQF level, 1..10
 * @param credits       credit value, positive
 * @param saqaQualId    SAQA registration id, optional
 */
public record AddQualificationRequest(
        @NotBlank(message = "the qualification's title is required")
        @Size(max = 200, message = "the title is longer than the register allows")
        String title,

        @Min(value = 1, message = "NQF level runs from 1 to 10")
        @Max(value = 10, message = "NQF level runs from 1 to 10")
        int nqfLevel,

        @Min(value = 1, message = "credits must be positive")
        @Max(value = 2000, message = "that is more credits than any qualification carries")
        int credits,

        @Size(max = 32, message = "the SAQA id is longer than the register allows")
        String saqaQualId) {
}
