package zw.ac.qvs.credential.adapter.in;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * A request to mint a share token.
 *
 * @param ttlDays how long it should last, or null for the configured default
 * @param label   a note so the holder can tell their tokens apart when withdrawing one
 */
public record ShareTokenRequest(
        @Min(value = 1, message = "a share token must last at least a day")
        @Max(value = 365, message = "a share token may not last more than a year")
        Integer ttlDays,

        @Size(max = 100, message = "a label may be at most 100 characters")
        String label) {
}
